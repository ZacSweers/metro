/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.asName
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.abstractFunctions
import dev.zacsweers.lattice.compiler.fir.constructType
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.isGraphFactory
import dev.zacsweers.lattice.compiler.fir.joinToRender
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.latticeFirBuiltIns
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.lattice.compiler.fir.requireContainingClassSymbol
import dev.zacsweers.lattice.compiler.mapToArray
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.isOperator
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
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
import org.jetbrains.kotlin.fir.types.withArguments
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
 *   class $$LatticeGraph : AppGraph {
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
 *     operator fun invoke(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   class $$LatticeGraph : AppGraph {
 *     constructor(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph)
 *   }
 *
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     operator fun invoke(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph): AppGraph
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
 *     fun create(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   class $$LatticeGraph : AppGraph {
 *     constructor(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph)
 *   }
 *
 *   @DependencyGraph.Factory
 *   abstract class Factory {
 *     fun create(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *
 *     object $$Impl : Factory() {
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
internal class DependencyGraphFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  companion object {
    private val PLACEHOLDER_SAM_FUNCTION = "$\$PLACEHOLDER_FOR_SAM".asName()
  }

  private val dependencyGraphAnnotationPredicate by unsafeLazy {
    annotated(
      (session.latticeClassIds.dependencyGraphAnnotations +
          session.latticeClassIds.dependencyGraphFactoryAnnotations)
        .map(ClassId::asSingleFqName)
    )
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(dependencyGraphAnnotationPredicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (classSymbol.isLocal) return emptySet()
    val names = mutableSetOf<Name>()
    if (classSymbol.isDependencyGraph(session)) {
      log("Found graph ${classSymbol.classId}")
      val classId = classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeGraph)
      names += classId.shortClassName

      val hasCompanion =
        classSymbol.declarationSymbols.any { it is FirClassSymbol<*> && it.isCompanion }
      if (!hasCompanion) {
        // Generate a companion for us to generate these functions on to
        names += SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
      }
    } else if (classSymbol.isGraphFactory(session)) {
      log("Found graph factory ${classSymbol.classId}")
      val shouldGenerateImpl = !classSymbol.isInterface
      if (shouldGenerateImpl) {
        names += LatticeSymbols.Names.latticeImpl
      }
    }

    if (names.isNotEmpty()) {
      log("Will generate classifiers into ${classSymbol.classId}: $names")
    }
    return names
  }

  private fun log(message: String) {
    if (session.latticeFirBuiltIns.options.debug) {
      //    if (true) {
      // TODO what's the better way to log?
      println("[LATTICE] $message")
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
            LatticeKeys.LatticeGraphCreatorsObjectDeclaration
          } else {
            LatticeKeys.Default
          }

        log("Generating companion object for ${owner.classId}")
        createCompanionObject(owner, key).symbol
      }
      LatticeSymbols.Names.latticeGraph -> {
        log("Generating graph class")
        createNestedClass(owner, name, LatticeKeys.LatticeGraphDeclaration) {
            superType(owner::constructType)
            for (typeParam in owner.typeParameterSymbols) {
              typeParameter(typeParam.name, variance = typeParam.variance) {
                for (bound in typeParam.resolvedBounds) {
                  bound(bound.coneType)
                }
              }
            }
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
      }
      LatticeSymbols.Names.latticeImpl -> {
        log("Generating factory impl")
        createNestedClass(
            owner,
            name,
            LatticeKeys.LatticeGraphFactoryImplDeclaration,
            classKind = ClassKind.OBJECT,
          ) {
            // Owner is always the factory class
            superType(owner::constructType)
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
      }
      else -> null
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
     * 2. A graph factory interface's $$Impl declaration (if it's not an interface).
     *
     * In either case, we'll just generate a constructor and a PLACEHOLDER_SAM_FUNCTION. The
     * placeholder is important because not everything about a creator is resolvable at
     * this point, but we can use this marker later to indicate we expect generateFunctions()
     * to generate the correct functions .
     */
    val isGraphCompanion =
      classSymbol.isCompanion &&
        classSymbol.requireContainingClassSymbol().isDependencyGraph(session)
    val isCreatorImpl =
      isGraphCompanion || classSymbol.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)
    if (isCreatorImpl) {
      names += SpecialNames.INIT
      names += PLACEHOLDER_SAM_FUNCTION
    } else if (classSymbol.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
      // $$LatticeGraph, generate a constructor
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
          createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
        } catch (e: IllegalArgumentException) {
          // TODO why does this happen in the IDE?
          throw RuntimeException(
            "Could not create private constructor for object ${context.owner.classId}",
            e,
          )
        }
      } else if (context.owner.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
        log("Generating graph constructor")
        // Create a constructor with parameters copied from the creator
        val creator =
          graphObject(context.owner.requireContainingClassSymbol())
            ?.findCreator(session, "generateConstructors for ${context.owner.classId}", ::log)
        log("Generating graph has creator? $creator")
        val samFunction = creator?.findSamFunction(session)
        createConstructor(
            context.owner,
            LatticeKeys.Default,
            isPrimary = true,
            generateDelegatedNoArgConstructorCall = true,
          ) {
            if (creator != null) {
              log("Generating graph SAM - ${samFunction?.callableId}")
              samFunction?.valueParameterSymbols?.forEach { valueParameterSymbol ->
                log("Generating SAM param ${valueParameterSymbol.name}")
                valueParameter(
                  name = valueParameterSymbol.name,
                  key = LatticeKeys.ValueParameter,
                  typeProvider = {
                    valueParameterSymbol.resolvedReturnType.withArguments(
                      it.mapToArray(FirTypeParameterRef::toConeType)
                    )
                  },
                )
              }
            }
          }
          .apply {
            // Copy annotations over. Workaround for https://youtrack.jetbrains.com/issue/KT-74361/
            for ((i, parameter) in samFunction?.valueParameterSymbols.orEmpty().withIndex()) {
              val parameterToUpdate = valueParameters[i]
              parameterToUpdate.replaceAnnotationsSafe(parameter.annotations)
            }
          }
      } else if (context.owner.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
        createConstructor(
          context.owner,
          LatticeKeys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        )
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
            LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration,
            function.name,
            returnTypeProvider = {
              try {
                // TODO would be nice to resolve this appropriately with the correct type arguments,
                //  but for now we always know this returns the graph type. FIR checker will check
                //  this too
                target.constructType(it.mapToArray(FirTypeParameter::toConeType))
              } catch (e: Exception) {
                throw AssertionError("Could not resolve return type for $callableId", e)
              }
            },
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
                key = LatticeKeys.ValueParameter,
                typeProvider = {
                  parameter.resolvedReturnType.withArguments(
                    it.mapToArray(FirTypeParameterRef::toConeType)
                  )
                },
              )
            }
          }
          .apply {
            // Copy annotations over. Workaround for https://youtrack.jetbrains.com/issue/KT-74361/
            for ((i, parameter) in function.valueParameterSymbols.withIndex()) {
              val parameterToUpdate = valueParameters[i]
              parameterToUpdate.replaceAnnotationsSafe(parameter.annotations)
            }
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
              LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration,
              LatticeSymbols.Names.invoke,
              returnTypeProvider = {
                graphClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
              },
            ) {
              status { isOperator = true }
            }
          functions += generatedFunction.symbol
        } else if (creator.isInterface) {
          // It's an interface creator, generate the SAM function
          val samFunction = creator.findSamFunction(session)
          log("Generating graph creator function $samFunction")
          samFunction?.let { functions += generateSAMFunction(graphObject.classSymbol, it) }
        } else {
          // Companion object factory function, i.e. factory()
          val creatorClass = creator.classSymbol
          val generatedFunction =
            createMemberFunction(
              owner,
              LatticeKeys.LatticeGraphFactoryCompanionGetter,
              LatticeSymbols.Names.factoryFunctionName,
              returnTypeProvider = {
                creatorClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
              },
            )
          functions += generatedFunction.symbol
        }
      }
    } else if (owner.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      // Graph factory $$Impl class, just generate the SAM function
      log("Generating factory impl into ${owner.classId}")
      val graphClass = owner.requireContainingClassSymbol().requireContainingClassSymbol()
      val graphObject = graphObject(graphClass)!!
      val creator =
        graphObject.findCreator(session, "generateFunctions ${context.owner.classId}", ::log)!!
      val samFunction = creator.findSamFunction(session)
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
    fun findCreator(session: FirSession, context: String, log: (String) -> Unit): Creator? {
      val creator =
        classSymbol.declarationSymbols
          .filterIsInstance<FirClassSymbol<*>>()
          .onEach {
            log(
              "Declaration factory candidate ${it.name}. Annotations are ${it.annotations.joinToRender()}"
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

      fun findSamFunction(session: FirSession): FirFunctionSymbol<*>? {
        return classSymbol.abstractFunctions(session).let {
          if (it.size == 1) {
            it[0]
          } else {
            // This is an invalid factory, let the checker notify this diagnostic layer
            null
          }
        }
      }
    }
  }
}
