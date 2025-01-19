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
import dev.zacsweers.lattice.compiler.fir.generators.DependencyGraphFirGenerator.GraphObject.Creator
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.isGraphFactory
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
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

  class GraphObject(val classSymbol: FirClassSymbol<*>, val creator: Creator?) {
    class Creator(val classSymbol: FirClassSymbol<*>) {
      private var samComputed = false
      val isInterface = classSymbol.isInterface

      var function: FirFunctionSymbol<*>? = null

      fun computeSAMFactoryFunction(session: FirSession) {
        if (samComputed) return
        function =
          classSymbol.abstractFunctions(session).let {
            if (it.size == 1) {
              it.single()
            } else {
              // This is an invalid factory, let the checker notify this diagnostic layer
              null
            }
          }
        samComputed = true
      }
    }
  }

  private val graphObjects = mutableMapOf<ClassId, GraphObject>()

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (classSymbol.isLocal) return emptySet()
    val names = mutableSetOf<Name>()
    if (classSymbol.isDependencyGraph(session)) {
      val creator =
        classSymbol.declarationSymbols
          .find { it is FirClassSymbol<*> && it.isGraphFactory(session) }
          ?.let { Creator(it as FirClassSymbol<*>) }
      graphObjects[classSymbol.classId] = GraphObject(classSymbol, creator)
      val classId = classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeGraph)
      names += classId.shortClassName

      val hasCompanion =
        classSymbol.declarationSymbols.any { it is FirClassSymbol<*> && it.isCompanion }
      if (!hasCompanion) {
        // Generate a companion for us to generate these functions on to
        names += SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
      }
    } else if (classSymbol.isGraphFactory(session)) {
      val shouldGenerateImpl = !classSymbol.isInterface
      if (shouldGenerateImpl) {
        names += LatticeSymbols.Names.latticeImpl
      }
    }

    return names
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
        createCompanionObject(owner, key).symbol
      }
      LatticeSymbols.Names.latticeGraph -> {
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
        // TODO if there's no parameters to the function, we could just make this an object
        val graphObject = graphObjects[owner.requireContainingClassSymbol().classId] ?: return null
        val creator = graphObject.creator?.classSymbol ?: return null
        createNestedClass(
            owner,
            name,
            LatticeKeys.LatticeGraphFactoryImplDeclaration,
            classKind = ClassKind.OBJECT,
          ) {
            superType(creator::constructType)
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
    if (classSymbol.isCompanion) {
      // Graph class companion objects get creators
      val graphClass = classSymbol.requireContainingClassSymbol()
      val graphObject = graphObjects[graphClass.classId] ?: return emptySet()
      names += SpecialNames.INIT
      val creator = graphObject.creator
      if (creator != null) {
        if (creator.isInterface) {
          // We can put the sam factory function on the companion
          // TODO this isn't safe with supertype gen, but for existing companions it also fails?
          //  creator.computeSAMFactoryFunction(session)
          //  creator.function?.let { names += it.name }
        } else {
          // We will just generate a `factory()` function
          names += LatticeSymbols.Names.factoryFunctionName
        }
      } else {
        // We'll generate a default invoke function
        names += LatticeSymbols.Names.invoke
      }
    } else if (classSymbol.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
      // LatticeGraph, generate a constructor
      names += SpecialNames.INIT
    } else if (classSymbol.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      // Graph factory impl, generating a constructor and its SAM function
      // Get the enclosing graph ID
      val graphClassId =
        classSymbol.requireContainingClassSymbol().requireContainingClassSymbol().classId
      val creator =
        graphObjects[graphClassId]?.creator
          ?: error("Could not find graph class ID $graphClassId in objects $graphObjects")
      // We can put the sam factory function on the companion
      creator.computeSAMFactoryFunction(session)
      names += SpecialNames.INIT
      creator.function?.let { names += it.name }
    }

    return names
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
      } else if (context.owner.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
        // Create a constructor with parameters copied from the creator
        val creator =
          graphObjects.getValue(context.owner.requireContainingClassSymbol().classId).creator
        createConstructor(
            context.owner,
            LatticeKeys.Default,
            isPrimary = true,
            generateDelegatedNoArgConstructorCall = true,
          ) {
            if (creator != null) {
              creator.computeSAMFactoryFunction(session)
              creator.function?.valueParameterSymbols?.forEach { valueParameterSymbol ->
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
            for ((i, parameter) in creator?.function?.valueParameterSymbols.orEmpty().withIndex()) {
              val parameterToUpdate = valueParameters[i]
              parameterToUpdate.replaceAnnotations(parameter.annotations)
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
    val owner = context?.owner ?: return emptyList()

    val generateSAMFunction: (FirClassSymbol<*>, FirFunctionSymbol<*>) -> FirNamedFunctionSymbol =
      { target, function ->
        createMemberFunction(
            owner,
            LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration,
            callableId.callableName,
            returnTypeProvider = {
              try {
                // TODO would be nice to resolve this appropriately with the correct type arguments,
                //  but for now we always know this returns the graph type. FIR checker will check
                // this too
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
            for (parameter in function.valueParameterSymbols) {
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
              parameterToUpdate.replaceAnnotations(parameter.annotations)
            }
          }
          .symbol
      }

    if (owner.isCompanion) {
      val graphClass = owner.requireContainingClassSymbol()
      val graphObject = graphObjects[graphClass.classId] ?: return emptyList()
      when (callableId.callableName) {
        LatticeSymbols.Names.factoryFunctionName -> {
          // Companion object factory function, i.e. factory()
          val creatorClass = graphObject.creator!!.classSymbol
          val generatedFunction =
            createMemberFunction(
              owner,
              LatticeKeys.LatticeGraphFactoryCompanionGetter,
              callableId.callableName,
              returnTypeProvider = {
                creatorClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
              },
            )
          return listOf(generatedFunction.symbol)
        }
        else -> {
          val creator = graphObject.creator
          return if (creator == null) {
            // Companion object invoke function, i.e. no creator
            check(callableId.callableName == LatticeSymbols.Names.invoke)
            val generatedFunction =
              createMemberFunction(
                owner,
                LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration,
                callableId.callableName,
                returnTypeProvider = {
                  graphClass.constructType(it.mapToArray(FirTypeParameter::toConeType))
                },
              ) {
                status { isOperator = true }
              }
            listOf(generatedFunction.symbol)
          } else {
            // It's an interface creator, generate the SAM function
            creator.function?.let { listOf(generateSAMFunction(graphObject.classSymbol, it)) }
              ?: emptyList()
          }
        }
      }
    }

    // Graph factory $$Impl class, just generate the SAM function
    if (owner.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      val graphClassId = owner.requireContainingClassSymbol().requireContainingClassSymbol().classId
      val graphObject =
        graphObjects[graphClassId]
          ?: error("Could not find graph class id $graphClassId in objects $graphObjects")
      val creator = graphObject.creator!!
      return creator.function?.let { listOf(generateSAMFunction(graphObject.classSymbol, it)) }
        ?: emptyList()
    }

    return emptyList()
  }
}
