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
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.abstractFunctions
import dev.zacsweers.lattice.compiler.fir.constructType
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.isGraphFactory
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.fir.requireContainingClassSymbol
import dev.zacsweers.lattice.compiler.mapToArray
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getContainingDeclaration
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.withArguments
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Generates implementation classes for `@DependencyGraph` types.
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
 *   class $$LatticeGraph : AppGraph {
 *     constructor()
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
// TODO need to compute supertype for companion I think
internal class DependencyGraphFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val dependencyGraphAnnotationPredicate by unsafeLazy {
    annotated(
      (session.latticeClassIds.dependencyGraphAnnotations +
          session.latticeClassIds.dependencyGraphFactoryAnnotations)
        .map { it.asSingleFqName() }
    )
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(dependencyGraphAnnotationPredicate)
  }

  class GraphObject(val classSymbol: FirClassSymbol<*>) {
    var creator: Creator? = null

    class Creator(val classSymbol: FirClassSymbol<*>) {
      private var samComputed = false
      val isInterface = classSymbol.classKind == ClassKind.INTERFACE

      lateinit var function: FirFunctionSymbol<*>

      fun computeSAMFactoryFunction(session: FirSession) {
        if (samComputed) return
        function = classSymbol.abstractFunctions(session).single()
        samComputed = true
      }
    }
  }

  private val graphObjects = mutableMapOf<ClassId, GraphObject>()

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (classSymbol.isDependencyGraph(session)) {
      graphObjects[classSymbol.classId] = GraphObject(classSymbol)
      return buildSet {
        val classId = classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeGraph)
        add(classId.shortClassName)

        val hasCompanion =
          classSymbol.declarationSymbols.any { it is FirClassSymbol<*> && it.isCompanion }
        if (!hasCompanion) {
          // Generate a companion for us to generate these functions on to
          add(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
        }
      }
    } else if (classSymbol.isGraphFactory(session)) {
      val graph = graphObjects[classSymbol.requireContainingClassSymbol().classId] ?: return emptySet()
      val creator = GraphObject.Creator(classSymbol)
      graph.creator = creator
      if (!creator.isInterface) {
        return setOf(LatticeSymbols.Names.latticeImpl)
      }
    }

    return emptySet()
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    // Impl class or companion
    return when (name) {
      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> {
        // It's a companion object, just generate the declaration
        val graphObject = graphObjects[owner.classId]
        val key =
          if (graphObject != null) {
            LatticeKeys.LatticeGraphCreatorsObjectDeclaration
          } else {
            LatticeKeys.Default
          }
        createCompanionObject(owner, key) {
            val creator = graphObject?.creator ?: return@createCompanionObject
            // If we have an interface creator, we'll implement it here
            if (creator.isInterface) {
              superType { creator.classSymbol.constructType(it) }
            }
          }
          .symbol
      }
      LatticeSymbols.Names.latticeGraph -> {
        createNestedClass(owner, name, LatticeKeys.LatticeGraphDeclaration) {
            superType { owner.constructType(it) }
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
      }
      LatticeSymbols.Names.latticeImpl -> {
        // TODO if there's no parameters to the function, we could just make this an object
        val graphObject = graphObjects[owner.requireContainingClassSymbol().classId] ?: return null
        val creator = graphObject.creator?.classSymbol ?: return null
        createNestedClass(
            owner,
            name,
            LatticeKeys.LatticeGraphFactoryImplDeclaration,
            classKind = ClassKind.OBJECT,
          ) {
            superType { creator.constructType(it) }
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

    return if (classSymbol.isCompanion) {
      // Graph class companion objects get creators
      val graphClass = classSymbol.getContainingClassSymbol() ?: return emptySet()
      val graphObject = graphObjects[graphClass.classId] ?: return emptySet()
      buildSet {
        add(SpecialNames.INIT)
        val creator = graphObject.creator
        if (creator != null) {
          if (creator.isInterface) {
            // We can put the sam factory function on the companion
            creator.computeSAMFactoryFunction(session)
            add(creator.function.name)
          } else {
            // We will just generate a `factory()` function
            add(LatticeSymbols.Names.factoryFunctionName)
          }
        } else {
          // We'll generate a default invoke function
          add(LatticeSymbols.Names.invoke)
        }
      }
    } else if (classSymbol.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
      // LatticeGraph, just generating a constructor here
      setOf(SpecialNames.INIT)
    } else if (classSymbol.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      // Graph factory impl, generating a constructor and its SAM function
      val creator =
        graphObjects.getValue(classSymbol.requireContainingClassSymbol().classId).creator!!
      // We can put the sam factory function on the companion
      creator.computeSAMFactoryFunction(session)
      buildSet {
        add(SpecialNames.INIT)
        add(creator.function.name)
      }
    } else {
      emptySet()
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
      } else if (context.owner.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
        // Create a constructor with parameters copied from the creator
        // TODO copy annotations too
        createConstructor(
          context.owner,
          LatticeKeys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          val creator =
            graphObjects.getValue(context.owner.requireContainingClassSymbol().classId).creator
          if (creator != null) {
            creator.computeSAMFactoryFunction(session)
            creator.function.valueParameterSymbols.forEach { valueParameterSymbol ->
              valueParameter(
                name = valueParameterSymbol.name,
                key = LatticeKeys.ValueParameter,
                typeProvider = {
                  valueParameterSymbol.resolvedReturnType.withArguments(
                    it.mapToArray { it.toConeType() }
                  )
                },
              )
            }
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

  @OptIn(SymbolInternals::class)
  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    // TODO factory impl, companion create, graph factory SAM functions
    val owner = context?.owner ?: return emptyList()

    val generateSAMFunction: (GraphObject.Creator) -> FirNamedFunctionSymbol = { creator ->
      createMemberFunction(
          owner,
          LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration,
          callableId.callableName,
          returnTypeProvider = {
            creator.function.resolvedReturnType.withArguments(it.mapToArray { it.toConeType() })
          },
        ) {
          status { isOverride = true }
          for (parameter in creator.function.valueParameterSymbols) {
            valueParameter(
              name = parameter.name,
              key = LatticeKeys.ValueParameter,
              typeProvider = {
                parameter.resolvedReturnType.withArguments(it.mapToArray { it.toConeType() })
              },
            )
          }
        }
        .symbol
    }

    if (owner.isCompanion) {
      val graphClass = owner.requireContainingClassSymbol()
      val graphObject = graphObjects[graphClass.classId] ?: return emptyList()
      when (callableId.callableName) {
        LatticeSymbols.Names.invoke -> {
          // Companion object invoke function, i.e. no creator
          check(graphObject.creator == null)
          val generatedFunction =
            createMemberFunction(
              owner,
              LatticeKeys.Default,
              callableId.callableName,
              returnTypeProvider = { graphClass.constructType(it.mapToArray { it.toConeType() }) },
            ) {
              status { isOperator = true }
            }
          return listOf(generatedFunction.symbol)
        }
        else -> {
          // It's an interface creator, generate the SAM function
          val creator = checkNotNull(graphObject.creator)
          return listOf(generateSAMFunction(creator))
        }
      }
    }

    // Graph factor $$Impl class, just generate the SAM function
    if (owner.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      val creator = graphObjects.getValue(owner.requireContainingClassSymbol().classId).creator!!
      return listOf(generateSAMFunction(creator))
    }

    return emptyList()
  }
}

/**
 * Generates factory supertypes onto companion objects of `@DependencyGraph` types IFF the graph has
 * a factory creator that is an interface.
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
 *
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     // ...
 *   }
 *
 *   // ----------------vv
 *   companion object : AppGraph.Factory {
 *     // ...
 *   }
 * }
 * ```
 */
internal class GraphFactoryFirSupertypeGenerationExtension(session: FirSession) :
  FirSupertypeGenerationExtension(session) {
  private val dependencyGraphAnnotationPredicate by unsafeLazy {
    annotated(
      (session.latticeClassIds.dependencyGraphAnnotations +
          session.latticeClassIds.dependencyGraphFactoryAnnotations)
        .map { it.asSingleFqName() }
    )
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(dependencyGraphAnnotationPredicate)
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    if (!declaration.symbol.isCompanion) return false
    val graphClass = declaration.getContainingDeclaration(session) ?: return false
    if (graphClass !is FirClass) return false
    val isGraph =
      graphClass.isAnnotatedWithAny(session, session.latticeClassIds.dependencyGraphAnnotations)
    if (!isGraph) return false
    val graphCreator =
      graphClass.declarations.filterIsInstance<FirClass>().firstOrNull {
        it.isAnnotatedWithAny(session, session.latticeClassIds.dependencyGraphFactoryAnnotations)
      }

    // TODO generics?
    if (graphCreator == null) return false
    if (!graphCreator.isInterface) return false

    // It's an interface so we can safely implement it
    return true
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val graphClass = classLikeDeclaration.getContainingDeclaration(session) ?: return emptyList()
    if (graphClass !is FirClass) return emptyList()

    val graphCreator =
      graphClass.declarations.filterIsInstance<FirClass>().firstOrNull {
        it.isAnnotatedWithAny(session, session.latticeClassIds.dependencyGraphFactoryAnnotations)
      } ?: return emptyList()

    // TODO generics?
    val graphCreatorType = graphCreator.defaultType()
    return listOf(graphCreatorType)
  }

  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<FirResolvedTypeRef> {
    // TODO is this needed for when we generate a companion object? Think not since we generate it
    // ourselves directly
    println("computeAdditionalSupertypesForGeneratedNestedClass: $klass")
    return super.computeAdditionalSupertypesForGeneratedNestedClass(klass, typeResolver)
  }
}
