// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.constructType
import dev.zacsweers.metro.compiler.fir.copyTypeParametersFrom
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.fir.isDependencyGraph
import dev.zacsweers.metro.compiler.fir.isGraphFactory
import dev.zacsweers.metro.compiler.fir.joinToRender
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.markImpl
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.nestedClasses
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.requireContainingClassSymbol
import dev.zacsweers.metro.compiler.mapToArray
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.isOperator
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Generates implementation class headers for `@DependencyGraph` types.
 *
 * _Note:_ If a graph already has a `companion object` declaration, it will be added to if graph
 * creator generation is enabled.
 *
 * ## Graph generation with no arguments
 *
 * Given this example:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   val value: String
 *
 *   fun inject(thing: Thing)
 *
 *   class Impl : AppGraph {
 *     constructor()
 *
 *     override val value: String
 *
 *     override fun inject(thing: Thing)
 *   }
 *
 *   companion object {
 *     operator fun invoke(): AppGraph
 *   }
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * val appGraph = AppGraph()
 * ```
 *
 * ## Graph generation with factory interface
 *
 * Given this example:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     operator fun invoke(@Provides int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   class Impl : AppGraph {
 *     constructor(@Provides int: Int, analyticsGraph: AnalyticsGraph)
 *   }
 *
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     operator fun invoke(@Provides int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 *
 *   companion object : AppGraph.Factory {
 *     override fun invoke(int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * val appGraph = AppGraph(int = 0, analyticsGraph = analyticsGraph)
 * ```
 *
 * ## Graph generation with factory abstract class
 *
 * If your creator factory is an abstract class, you will need to access it via generated
 * `factory()` function.
 *
 * Given this example:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   @DependencyGraph.Factory
 *   abstract class Factory {
 *     fun create(@Provides int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   class Impl : AppGraph {
 *     constructor(@Provides int: Int, analyticsGraph: AnalyticsGraph)
 *   }
 *
 *   @DependencyGraph.Factory
 *   abstract class Factory {
 *     fun create(@Provides int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *
 *     object Impl : Factory() {
 *       override fun create(int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *     }
 *   }
 *
 *   companion object {
 *     fun factory(): Factory
 *   }
 * }
 * ```
 *
 * Usage:
 * ```kotlin
 * val appGraph = AppGraph.factory().create(int = 0, analyticsGraph = analyticsGraph)
 * ```
 */
internal class DependencyGraphFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

  companion object {
    private val PLACEHOLDER_SAM_FUNCTION = $$$"$$PLACEHOLDER_FOR_SAM".asName()
  }

  private val graphImpls = mutableSetOf<ClassId>()
  private val factoryImpls = mutableSetOf<ClassId>()

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.dependencyGraphAndFactoryPredicate)
  }

  @OptIn(DirectDeclarationsAccess::class)
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (classSymbol.isLocal) return emptySet()
    val names = mutableSetOf<Name>()
    if (classSymbol.isDependencyGraph(session)) {
      log("Found graph ${classSymbol.classId}")

      var hasCompanion = false
      // reserve names for existing nested class names
      val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
      for (nested in context.nestedClasses()) {
        nameAllocator.newName(nested.name)
        if (nested.isCompanion) {
          hasCompanion = true
        }
      }

      val classId =
        classSymbol.classId.createNestedClassId(nameAllocator.newName(Symbols.Names.Impl))
      graphImpls += classId
      names += classId.shortClassName

      if (!hasCompanion) {
        // Generate a companion for us to generate these functions on to
        names += SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
      }
    } else if (classSymbol.isGraphFactory(session)) {
      log("Found graph factory ${classSymbol.classId}")
      val classId = classSymbol.classId.createNestedClassId(Symbols.Names.Impl)
      factoryImpls += classId
      // Always generate this impl though we may not use it. It's just easier to do it this way in
      // FIR unfortunately due to lifecycles
      names += classId.shortClassName
    }

    if (names.isNotEmpty()) {
      log("Will generate classifiers into ${classSymbol.classId}: $names")
    }
    return names
  }

  private fun log(message: String) {
    if (session.metroFirBuiltIns.options.debug) {
      //    if (true) {
      // TODO what's the better way to log?
      println("[METRO] $message")
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    log("Generating nested class $name into ${owner.classId}")
    // Impl class or companion
    return when (name) {
      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> {
        // It's a companion object, just generate the declaration
        val isGraph = owner.isDependencyGraph(session)
        val key =
          if (isGraph) {
            Keys.MetroGraphCreatorsObjectDeclaration
          } else {
            Keys.Default
          }

        log("Generating companion object for ${owner.classId}")
        createCompanionObject(owner, key).symbol
      }

      else -> {
        val classId = owner.classId.createNestedClassId(name)
        when (classId) {
          in graphImpls -> {
            log("Generating graph class")
            createNestedClass(owner, name, Keys.GraphImplClassDeclaration) {
                superType(owner::constructType)
                copyTypeParametersFrom(owner, session)
              }
              .apply {
                markAsDeprecatedHidden(session)
                markImpl(session)
              }
              .symbol
          }

          in factoryImpls -> {
            log("Generating factory impl")
            createNestedClass(
                owner,
                name,
                Keys.GraphFactoryImplClassDeclaration,
                classKind = ClassKind.OBJECT,
              ) {
                // Owner is always the factory class
                superType(owner::constructType)
              }
              .apply {
                markAsDeprecatedHidden(session)
                markImpl(session)
              }
              .symbol
          }

          else -> {
            null
          }
        }
      }
    }
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val names = mutableSetOf<Name>()

    /*
     * There are two types of creator instances.
     * 1. A graph class's companion object. It will either implement the
     *    graph factory (if it's an interface) or expose a `factory()` accessor function.
     * 2. A graph factory interface's `Impl` declaration (if it's not an interface).
     *
     * In either case, we'll just generate a constructor and a PLACEHOLDER_SAM_FUNCTION. The
     * placeholder is important because not everything about a creator is resolvable at
     * this point, but we can use this marker later to indicate we expect generateFunctions()
     * to generate the correct functions.
     */
    val isGraphCompanion =
      classSymbol.isCompanion &&
        classSymbol.requireContainingClassSymbol().isDependencyGraph(session)
    val isCreatorImpl =
      isGraphCompanion || classSymbol.hasOrigin(Keys.GraphFactoryImplClassDeclaration)
    if (isCreatorImpl) {
      names += SpecialNames.INIT
      names += PLACEHOLDER_SAM_FUNCTION
    } else if (classSymbol.hasOrigin(Keys.GraphImplClassDeclaration)) {
      // `Impl`, generate a constructor
      names += SpecialNames.INIT
    }

    if (names.isNotEmpty()) {
      log("Will generate callables into ${classSymbol.classId}: $names")
    }
    return names
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        log("Generating companion object constructor for ${context.owner.classId}")
        try {
          createDefaultPrivateConstructor(context.owner, Keys.Default)
        } catch (e: IllegalArgumentException) {
          // TODO why does this happen in the IDE?
          throw RuntimeException(
            "Could not create private constructor for object ${context.owner.classId}",
            e,
          )
        }
      } else if (context.owner.hasOrigin(Keys.GraphImplClassDeclaration)) {
        log("Generating graph constructor")
        // Create a constructor with parameters copied from the creator
        val creator =
          graphObject(context.owner.requireContainingClassSymbol())
            ?.findCreator(session, "generateConstructors for ${context.owner.classId}", ::log)
        log("Generating graph has creator? $creator")
        val samFunction = creator?.classSymbol?.findSamFunction(session)
        createConstructor(
            context.owner,
            Keys.Default,
            isPrimary = true,
            generateDelegatedNoArgConstructorCall = true,
          ) {
            visibility = Visibilities.Private
            if (creator != null) {
              log("Generating graph SAM - ${samFunction?.callableId}")
              samFunction?.valueParameterSymbols?.forEach { valueParameterSymbol ->
                log("Generating SAM param ${valueParameterSymbol.name}")
                valueParameter(
                  name = valueParameterSymbol.name,
                  key = Keys.RegularParameter,
                  type = valueParameterSymbol.resolvedReturnType,
                )
              }
            }
          }
          .apply {
            // Copy annotations over. Workaround for https://youtrack.jetbrains.com/issue/KT-74361/
            for ((i, parameter) in samFunction?.valueParameterSymbols.orEmpty().withIndex()) {
              val parameterToUpdate = valueParameters[i]
              parameterToUpdate.replaceAnnotationsSafe(
                parameter.resolvedCompilerAnnotationsWithClassIds
              )
            }
          }
      } else if (context.owner.hasOrigin(Keys.GraphFactoryImplClassDeclaration)) {
        createConstructor(
          context.owner,
          Keys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          visibility = Visibilities.Private
        }
      } else {
        return emptyList()
      }
    return listOf(constructor.symbol)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    log("Generating function $callableId")
    val owner = context?.owner ?: return emptyList()

    val generateSAMFunction: (FirClassSymbol<*>, FirFunctionSymbol<*>) -> FirNamedFunctionSymbol =
      { target, function ->
        log("Generating creator SAM ${function.callableId} in ${target.classId}")
        createMemberFunction(
            owner,
            Keys.MetroGraphCreatorsObjectInvokeDeclaration,
            function.name,
            returnType = function.resolvedReturnType,
          ) {
            status {
              isOverride = !owner.isCompanion
              isOperator = function.isOperator
            }
            log("Generating ${function.valueParameterSymbols.size} parameters?")
            for (parameter in function.valueParameterSymbols) {
              log("Generating parameter ${parameter.name}")
              valueParameter(
                name = parameter.name,
                key = Keys.RegularParameter,
                type = parameter.resolvedReturnType,
              )
            }
          }
          .apply {
            // Copy annotations over. Workaround for https://youtrack.jetbrains.com/issue/KT-74361/
            for ((i, parameter) in function.valueParameterSymbols.withIndex()) {
              val parameterToUpdate = valueParameters[i]
              parameterToUpdate.replaceAnnotationsSafe(
                parameter.resolvedCompilerAnnotationsWithClassIds
              )
            }
            // Add our marker annotation
            replaceAnnotationsSafe(
              annotations +
                buildSimpleAnnotation {
                  session.metroFirBuiltIns.graphFactoryInvokeFunctionMarkerClassSymbol
                }
            )
          }
          .symbol
      }

    val functions = mutableListOf<FirNamedFunctionSymbol>()

    if (owner.isCompanion) {
      log("... into companion")
      val graphClass = owner.requireContainingClassSymbol()
      val graphObject = graphObject(graphClass) ?: return emptyList()
      if (callableId.callableName == PLACEHOLDER_SAM_FUNCTION) {
        val creator =
          graphObject.findCreator(session, "generateFunctions ${context.owner.classId}", ::log)
        if (creator == null) {
          // Generate a default invoke function
          log("Creator was null, generating a default invoke. ")
          val generatedFunction =
            createMemberFunction(
                owner,
                Keys.MetroGraphCreatorsObjectInvokeDeclaration,
                Symbols.Names.invoke,
                returnTypeProvider = {
                  graphClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
                },
              ) {
                status { isOperator = true }
              }
              .apply {
                // Add our marker annotation
                replaceAnnotationsSafe(
                  annotations +
                    buildSimpleAnnotation {
                      session.metroFirBuiltIns.graphFactoryInvokeFunctionMarkerClassSymbol
                    }
                )
              }
          functions += generatedFunction.symbol
        } else if (owner.implements(creator.classSymbol.classId, session)) {
          // Companion is the interface
          val samFunction = creator.classSymbol.findSamFunction(session)
          log("Generating graph creator function $samFunction")
          samFunction?.let { functions += generateSAMFunction(graphObject.classSymbol, it) }
        } else {
          // Companion object factory function, i.e. factory()
          val creatorClass = creator.classSymbol
          val generatedFunction =
            createMemberFunction(
              owner,
              Keys.MetroGraphFactoryCompanionGetter,
              Symbols.Names.factory,
              returnTypeProvider = {
                creatorClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
              },
            )
          functions += generatedFunction.symbol
        }
      }
    } else if (owner.hasOrigin(Keys.GraphFactoryImplClassDeclaration)) {
      // Graph factory `Impl` class, just generate the SAM function
      log("Generating factory impl into ${owner.classId}")
      val graphClass = owner.requireContainingClassSymbol().requireContainingClassSymbol()
      val graphObject =
        graphObject(graphClass) ?: reportCompilerBug("No graph object found for $graphClass")
      val creator =
        graphObject.findCreator(session, "generateFunctions ${context.owner.classId}", ::log)!!
      val samFunction = creator.classSymbol.findSamFunction(session)
      samFunction?.let { functions += generateSAMFunction(graphObject.classSymbol, it) }
    }

    if (functions.isNotEmpty()) {
      log(
        "Generated ${functions.size} for ${owner.classId}: ${functions.joinToString { it.name.asString() }}"
      )
    } else {
      log("Generated no functions for ${owner.classId}")
    }

    return functions
  }

  fun graphObject(classLikeSymbol: FirClassLikeSymbol<*>) =
    graphObject(classLikeSymbol as FirClassSymbol<*>)

  fun graphObject(classSymbol: FirClassSymbol<*>): GraphObject? {
    return if (classSymbol.isDependencyGraph(session)) {
      GraphObject(classSymbol)
    } else {
      null
    }
  }

  @JvmInline
  value class GraphObject(val classSymbol: FirClassSymbol<*>) {
    @OptIn(DirectDeclarationsAccess::class)
    fun findCreator(session: FirSession, context: String, log: (String) -> Unit): Creator? {
      val creator =
        classSymbol.declarationSymbols
          .filterIsInstance<FirClassSymbol<*>>()
          .onEach {
            log(
              "Declaration factory candidate ${it.name}. Annotations are ${it.resolvedCompilerAnnotationsWithClassIds.joinToRender()}"
            )
          }
          .find { it.isGraphFactory(session) }
          ?.let(::Creator)
      log("Creator found from $context? $creator.")
      return creator
    }

    @JvmInline
    value class Creator(val classSymbol: FirClassSymbol<*>) {
      val isInterface
        get() = classSymbol.isInterface
    }
  }
}
