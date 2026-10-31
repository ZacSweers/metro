// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.CompanionMode
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.buildStaticAnnotations
import dev.zacsweers.metro.compiler.fir.caching
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
import dev.zacsweers.metro.compiler.fir.typeRefFromQualifierParts
import dev.zacsweers.metro.compiler.mapToArray
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.reserveName
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds

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
  private val creatorTypeResolverFactory by lazy { MetroFirTypeResolver.Factory(session).caching() }

  // FIR can ask for an owner's callable names more than once.
  private val callableNamesByOwner: FirCache<FirClassSymbol<*>, Set<Name>, Unit> =
    session.firCachesFactory.createCache { owner, _ -> computeCallableNames(owner) }

  // FIR calls generateFunctions() once per reserved name, so resolve each owner's creator once.
  private val creatorPlans: FirCache<FirClassSymbol<*>, CreatorPlan?, Unit> =
    session.firCachesFactory.createCache { owner, _ -> computeCreatorPlan(owner) }

  private val companionMode: CompanionMode
    get() = session.metroFirBuiltIns.options.companionMode

  private val generatesCompanionCreators: Boolean
    get() = companionMode.generatesCompanionCreators

  private val generatesBlockCreators: Boolean
    get() = companionMode.generatesBlockCreators

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
        nameAllocator.reserveName(nested.name)
        if (nested.isCompanion) {
          hasCompanion = true
        }
      }

      if (!session.metroFirBuiltIns.options.generateClassesInIr) {
        val classId =
          classSymbol.classId.createNestedClassId(nameAllocator.newName(Symbols.Names.Impl))
        graphImpls += classId
        names += classId.shortClassName
      }

      if (!hasCompanion && generatesCompanionCreators) {
        // Generate a companion for us to generate these functions on to
        names += SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
      }
    } else if (
      classSymbol.isGraphFactory(session) &&
        classSymbol.getContainingClassSymbol()?.isDependencyGraph(session) == true
    ) {
      log("Found graph factory ${classSymbol.classId}")
      if (!session.metroFirBuiltIns.options.generateClassesInIr) {
        val classId = classSymbol.classId.createNestedClassId(Symbols.Names.Impl)
        factoryImpls += classId
        // Always generate this impl though we may not use it. It's just easier to do it this way in
        // FIR unfortunately due to lifecycles
        names += classId.shortClassName
      }
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
  ): Set<Name> = callableNamesByOwner.getValue(classSymbol, Unit)

  @OptIn(DirectDeclarationsAccess::class)
  private fun computeCallableNames(classSymbol: FirClassSymbol<*>): Set<Name> {
    val names = mutableSetOf<Name>()

    /*
     * There are three types of creator owners.
     * 1. A graph class's companion object. It either implements the graph factory (if it's an
     *    interface) or exposes a `factory()` accessor function.
     * 2. A graph factory's hidden `Impl` declaration.
     * 3. The graph itself when the mode generates companion-block creators.
     *
     * Creator names aren't always resolvable here. `PLACEHOLDER_SAM_FUNCTION` tells
     * `generateFunctions()` to generate a creator whose name wasn't reserved.
     */
    val isGraphCompanion =
      if (generatesCompanionCreators && classSymbol.isCompanion) {
        classSymbol.requireContainingClassSymbol().isDependencyGraph(session)
      } else {
        false
      }

    val isFactoryImpl = classSymbol.hasOrigin(Keys.GraphFactoryImplClassDeclaration)
    val isCreatorImpl = isGraphCompanion || isFactoryImpl
    val isBlockCreator = generatesBlockCreators && classSymbol.isDependencyGraph(session)

    if (isCreatorImpl || isBlockCreator) {
      if (isCreatorImpl) {
        names += SpecialNames.INIT
      }
      names += PLACEHOLDER_SAM_FUNCTION
      names += Symbols.Names.invoke
      names += Symbols.Names.factory
      if (isBlockCreator) {
        // FIR resolves a call like `AppGraph.create()` by name before it generates members.
        val creator =
          classSymbol.declarationSymbols.filterIsInstance<FirClassSymbol<*>>().firstOrNull {
            it.isGraphFactory(session)
          }
        if (creator != null) {
          names += declaredCreatorFunctionNames(creator)
        }
      } else if (isGraphCompanion) {
        // Reserve source function names so their overloads also see the generated override.
        classSymbol.declarationSymbols.filterIsInstance<FirNamedFunctionSymbol>().mapTo(names) {
          it.name
        }
      }
    } else if (classSymbol.hasOrigin(Keys.GraphImplClassDeclaration)) {
      // `Impl`, generate a constructor
      names += SpecialNames.INIT
    }

    if (names.isNotEmpty()) {
      log("Will generate callables into ${classSymbol.classId}: $names")
    }
    return names
  }

  /**
   * Returns the function names that [creator] and its supertypes declare. Source supertypes can
   * still be unresolved at this phase. Resolving them normally could reenter generated member
   * scopes, so this resolves their qualified names through the file's import scopes.
   */
  @OptIn(DirectDeclarationsAccess::class)
  private fun declaredCreatorFunctionNames(creator: FirClassSymbol<*>): Set<Name> {
    val names = mutableSetOf<Name>()
    // Any's members can't be factory functions.
    val visited = mutableSetOf(StandardClassIds.Any)

    fun collect(classSymbol: FirClassSymbol<*>) {
      if (!visited.add(classSymbol.classId)) {
        return
      }
      classSymbol.declarationSymbols.filterIsInstance<FirNamedFunctionSymbol>().forEach {
        names += it.name
      }

      val typeResolver = creatorTypeResolverFactory.create(classSymbol)
      val sourceClass =
        if (classSymbol.origin is FirDeclarationOrigin.Source) {
          classSymbol.moduleData.session.firProvider.getFirClassifierByFqName(classSymbol.classId)
            as? FirClass
        } else {
          null
        }

      val superTypeRefs =
        if (sourceClass != null) {
          sourceClass.superTypeRefs
        } else {
          classSymbol.resolvedSuperTypeRefs
        }

      for (superTypeRef in superTypeRefs) {
        val resolvedSuperType = superTypeRef.coneTypeOrNull
        val superType =
          if (resolvedSuperType != null) {
            resolvedSuperType
          } else {
            val userTypeRef = superTypeRef as? FirUserTypeRef ?: continue
            val source = userTypeRef.source ?: continue

            // Finding the class only needs its qualified name, so type arguments can stay
            // unresolved.
            val classifierTypeRef =
              typeRefFromQualifierParts(userTypeRef.isMarkedNullable, source) {
                for (qualifier in userTypeRef.qualifier) {
                  part(qualifier.name)
                }
              }

            typeResolver?.resolveType(classifierTypeRef) ?: continue
          }

        val superClass = superType.toRegularClassSymbol(session) ?: continue
        collect(superClass)
      }
    }

    collect(creator)
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
            // In NONE mode, createGraph() calls in other modules construct this class directly.
            visibility =
              if (companionMode == CompanionMode.NONE) {
                Visibilities.Public
              } else {
                Visibilities.Private
              }
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
                parameter.resolvedCompilerAnnotationsWithClassIds,
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

  /**
   * Resolves the creator that [owner] implements. [owner] is a graph companion, a graph that gets
   * companion-block creators, or a factory's hidden `Impl`. Returns null for any other class.
   */
  private fun computeCreatorPlan(owner: FirClassSymbol<*>): CreatorPlan? {
    if (owner.hasOrigin(Keys.GraphFactoryImplClassDeclaration)) {
      val graphClass =
        owner.requireContainingClassSymbol().requireContainingClassSymbol() as FirClassSymbol<*>
      val graphObject =
        graphObject(graphClass) ?: reportCompilerBug("No graph object found for $graphClass")
      val creator = graphObject.findCreator(session, "generateFunctions ${owner.classId}", ::log)!!
      val sam = creator.classSymbol.findSamFunction(session) ?: return null
      return CreatorPlan(graphClass, creator, sam, sam.name)
    }

    val isBlockCreator = generatesBlockCreators && owner.isDependencyGraph(session)
    val isCompanionCreator = generatesCompanionCreators && owner.isCompanion
    val isCreatorOwner = isBlockCreator || isCompanionCreator
    if (!isCreatorOwner) {
      return null
    }
    val graphClass =
      if (isBlockCreator) {
        owner
      } else {
        owner.requireContainingClassSymbol() as FirClassSymbol<*>
      }
    val graphObject = graphObject(graphClass) ?: return null
    val creator = graphObject.findCreator(session, "generateFunctions ${owner.classId}", ::log)
    val creatorClass = creator?.classSymbol

    var sam: FirNamedFunctionSymbol? = null
    if (creatorClass != null) {
      val isBlockSam = isBlockCreator && creatorClass.isInterface
      val generatesSam = isBlockSam || owner.implements(creatorClass.classId, session)
      if (generatesSam) {
        // A factory without a single abstract function has nothing to implement.
        sam = creatorClass.findSamFunction(session) ?: return null
      }
    }
    return CreatorPlan(graphClass, creator, sam, graphCreatorName(creatorClass, sam))
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    log("Generating function $callableId")
    val owner = context?.owner ?: return emptyList()
    val plan = creatorPlans.getValue(owner, Unit) ?: return emptyList()

    val requestedName = callableId.callableName
    val matchesCreatorName = requestedName == plan.creatorName
    // External extensions can supply a factory after this generator reserves the owner's names.
    val isPlaceholderRequest = requestedName == PLACEHOLDER_SAM_FUNCTION && plan.sam != null
    val isUnreservedName = plan.creatorName !in callableNamesByOwner.getValue(owner, Unit)
    val needsPlaceholderSam = isPlaceholderRequest && isUnreservedName
    val generatesCreator = matchesCreatorName || needsPlaceholderSam
    if (!generatesCreator) {
      return emptyList()
    }

    val isBlockCreator = generatesBlockCreators && owner.isDependencyGraph(session)

    // Creator markers let downstream intrinsics discover the producer's entry points.
    fun finishCreator(function: FirFunction, marksGraphCreator: Boolean): FirNamedFunctionSymbol {
      val extraAnnotations = buildList {
        if (marksGraphCreator) {
          add(
            buildSimpleAnnotation {
              session.metroFirBuiltIns.graphFactoryInvokeFunctionMarkerClassSymbol
            },
          )
        }
        // In compatibility mode, the block creator is the static entry point.
        if (owner.isCompanion && companionMode == CompanionMode.COMPANION_OBJECT) {
          addAll(buildStaticAnnotations(session))
        }
      }
      function.replaceAnnotationsSafe(function.annotations + extraAnnotations)

      val declaration =
        if (isBlockCreator) {
          function.markAsCompanionBlockMemberCompat(owner)
        } else {
          function
        }
      return declaration.symbol as FirNamedFunctionSymbol
    }

    fun generateSAMFunction(
      function: FirFunctionSymbol<*>,
      sourceDefaults: Set<Int> = emptySet(),
    ): FirNamedFunctionSymbol {
      val generated =
        createMemberFunction(
            owner,
            Keys.MetroGraphCreatorsObjectInvokeDeclaration,
            function.name,
            returnType = function.resolvedReturnType,
          ) {
            // These modifiers are syntactic, so the raw status is reliable before resolution.
            status {
              isOverride = !owner.isCompanion && !isBlockCreator
              isOperator = function.rawStatus.isOperator
              isSuspend = function.rawStatus.isSuspend
            }
            for ((index, parameter) in function.valueParameterSymbols.withIndex()) {
              valueParameter(
                name = parameter.name,
                key = Keys.RegularParameter,
                type = parameter.resolvedReturnType,
                isVararg = parameter.isVararg,
                isCrossinline = parameter.isCrossinline,
                isNoinline = parameter.isNoinline,
                hasDefaultValue = index in sourceDefaults,
              )
            }
          }
          .apply {
            // Copy factory input annotations to preserve bindings.
            // Work around https://youtrack.jetbrains.com/issue/KT-74361/.
            for ((i, parameter) in function.valueParameterSymbols.withIndex()) {
              valueParameters[i].replaceAnnotationsSafe(
                parameter.resolvedCompilerAnnotationsWithClassIds,
              )
            }
          }
      return finishCreator(generated, marksGraphCreator = true)
    }

    val graphClass = plan.graphClass
    val creator = plan.creator
    val sam = plan.sam
    val function =
      if (creator == null) {
        // Block functions can't see the graph's type parameters, so the creator declares copies.
        val generatedFunction =
          createMemberFunction(
            owner,
            Keys.MetroGraphCreatorsObjectInvokeDeclaration,
            Symbols.Names.invoke,
            returnTypeProvider = {
              graphClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
            },
          ) {
            copyTypeParametersFrom(graphClass, session, includeBounds = true)
            status { isOperator = true }
          }
        finishCreator(generatedFunction, marksGraphCreator = true)
      } else if (sam != null) {
        // Block creators don't override the SAM, so they copy the defaults they can see.
        val defaults =
          if (isBlockCreator) {
            creator.classSymbol.sourceFactoryDefaults(sam, session)
          } else {
            emptySet()
          }
        generateSAMFunction(sam, defaults)
      } else {
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
        finishCreator(generatedFunction, marksGraphCreator = false)
      }

    log("Generated ${function.name} for ${owner.classId}")
    return listOf(function)
  }

  /** The creator that a companion, block, or factory `Impl` owner implements. */
  private class CreatorPlan(
    val graphClass: FirClassSymbol<*>,
    val creator: GraphObject.Creator?,
    /** The factory function to implement. It's null for `invoke()` and `factory()` creators. */
    val sam: FirNamedFunctionSymbol?,
    val creatorName: Name,
  )

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
              "Declaration factory candidate ${it.name}. Annotations are ${it.resolvedCompilerAnnotationsWithClassIds.joinToRender()}",
            )
          }
          .find { it.isGraphFactory(session) }
          ?.let(::Creator)
      if (creator != null) {
        log("Creator found from $context? $creator.")
        return creator
      }

      // Fall back to scope-based lookup for classifiers generated by external FIR extensions
      // (which are visible in scope but not in declarationSymbols).
      val scopeCreator =
        classSymbol.nestedClasses(session).find { it.isGraphFactory(session) }?.let(::Creator)
      log("Creator found from $context (scope fallback)? $scopeCreator.")
      return scopeCreator
    }

    @JvmInline
    value class Creator(val classSymbol: FirClassSymbol<*>) {
      val isInterface
        get() = classSymbol.isInterface
    }
  }
}
