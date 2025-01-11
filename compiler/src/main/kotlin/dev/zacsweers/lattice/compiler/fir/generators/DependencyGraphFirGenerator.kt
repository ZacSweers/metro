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
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.abstractFunctions
import dev.zacsweers.lattice.compiler.fir.callableDeclarations
import dev.zacsweers.lattice.compiler.fir.constructType
import dev.zacsweers.lattice.compiler.fir.copyParameters
import dev.zacsweers.lattice.compiler.fir.generateMemberFunction
import dev.zacsweers.lattice.compiler.fir.generators.DependencyGraphFirGenerator.GraphObject.Creator
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isBinds
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.isGraphFactory
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.fir.requireContainingClassSymbol
import dev.zacsweers.lattice.compiler.latticeAnnotations
import dev.zacsweers.lattice.compiler.mapToArray
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.builder.buildReceiverParameter
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.renderReadable
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
        .map { it.asSingleFqName() }
    )
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(dependencyGraphAnnotationPredicate)
  }

  class GraphObject(val classSymbol: FirClassSymbol<*>, val creator: Creator?) {
    private var overridesComputed = false

    val bindsOrAccessors = mutableMapOf<Name, FirCallableSymbol<*>>()
    val injectors = mutableMapOf<Name, FirNamedFunctionSymbol>()

    fun computeAccessorsAndInjectors(session: FirSession) {
      if (overridesComputed) return
      val types = sequenceOf(classSymbol.defaultType()).plus(classSymbol.getSuperTypes(session))
      for (type in types) {
        val clazz = type.toClassSymbol(session) ?: continue
        if (clazz.classId == LatticeSymbols.ClassIds.anyClass) continue

        for (callable in
          clazz.callableDeclarations(session, includeSelf = true, includeAncestors = false)) {

          val annotations = callable.latticeAnnotations(session)
          if (annotations.isProvides) {
            // Provider function, skip (for now?)
            continue
          }

          // Abstract check is important. We leave alone any non-providers/injectors
          if (callable.isAbstract) {
            when (callable) {
              is FirNamedFunctionSymbol -> {
                if (callable.valueParameterSymbols.isEmpty() || callable.isBinds(session)) {
                  bindsOrAccessors[callable.name] = callable
                } else {
                  // FIR checker will ensure only one parameter
                  injectors[callable.name] = callable
                }
              }
              is FirPropertySymbol -> {
                bindsOrAccessors[callable.name] = callable
              }
            }
          }
        }
      }
      bindsOrAccessors.stableSort { c1, c2 ->
        if (c1 is FirPropertySymbol && c2 is FirPropertySymbol) {
          c1.resolvedReturnType.renderReadable().compareTo(c2.resolvedReturnType.renderReadable())
        } else if (c1 is FirNamedFunctionSymbol && c2 is FirNamedFunctionSymbol) {
          if (c1.valueParameterSymbols.size != c2.valueParameterSymbols.size) {
            c1.valueParameterSymbols.size.compareTo(c2.valueParameterSymbols.size)
          } else {
            c1.resolvedReturnType.renderReadable().compareTo(c2.resolvedReturnType.renderReadable())
          }
        } else if (c1 is FirPropertySymbol) {
          -1
        } else {
          1
        }
      }
      injectors.stableSort { c1, c2 ->
        if (c1.valueParameterSymbols.size != c2.valueParameterSymbols.size) {
          c1.valueParameterSymbols.size.compareTo(c2.valueParameterSymbols.size)
        } else {
          c1.resolvedReturnType.renderReadable().compareTo(c2.resolvedReturnType.renderReadable())
        }
      }
      overridesComputed = true
    }

    private fun <V> MutableMap<Name, V>.stableSort(comparator: Comparator<V>) {
      val copy = entries.toSet()
      clear()
      putAll(
        copy
          .sortedWith(
            compareBy<Map.Entry<Name, V>> { it.key }
              .thenComparator { (_, v1), (_, v2) -> comparator.compare(v1, v2) }
          )
          .associate { it.key to it.value }
      )
    }

    class Creator(val classSymbol: FirClassSymbol<*>) {
      private var samComputed = false
      val isInterface = classSymbol.classKind == ClassKind.INTERFACE

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
    if (classSymbol.isDependencyGraph(session)) {
      val creator =
        classSymbol.declarationSymbols
          .find { it is FirClassSymbol<*> && it.isGraphFactory(session) }
          ?.let { Creator(it as FirClassSymbol<*>) }
      graphObjects[classSymbol.classId] = GraphObject(classSymbol, creator)
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
      if (!classSymbol.isInterface) {
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
      val graphClass = classSymbol.requireContainingClassSymbol()
      val graphObject = graphObjects[graphClass.classId] ?: return emptySet()
      buildSet {
        add(SpecialNames.INIT)
        val creator = graphObject.creator
        if (creator != null) {
          if (creator.isInterface) {
            // We can put the sam factory function on the companion
            creator.computeSAMFactoryFunction(session)
            creator.function?.let { add(it.name) }
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
      // LatticeGraph, generate a constructor and gather all accessors and injectors
      val names = mutableSetOf<Name>(SpecialNames.INIT)

      // Gather accessors and injectors
      val graphClass = classSymbol.requireContainingClassSymbol()
      val graphObject = graphObjects.getValue(graphClass.classId)
      graphObject.computeAccessorsAndInjectors(session)
      names.addAll(graphObject.bindsOrAccessors.keys)
      names.addAll(graphObject.injectors.keys)

      names
    } else if (classSymbol.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      // Graph factory impl, generating a constructor and its SAM function
      val creator =
        graphObjects.getValue(classSymbol.requireContainingClassSymbol().classId).creator!!
      // We can put the sam factory function on the companion
      creator.computeSAMFactoryFunction(session)
      buildSet {
        add(SpecialNames.INIT)
        creator.function?.let { add(it.name) }
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
                      it.mapToArray { it.toConeType() }
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

  @OptIn(SymbolInternals::class)
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
                target.constructType(it.mapToArray { it.toConeType() })
              } catch (e: Exception) {
                throw AssertionError("Could not resolve return type for ${callableId}", e)
              }
            },
          ) {
            status { isOverride = true }
            for (parameter in function.valueParameterSymbols) {
              valueParameter(
                name = parameter.name,
                key = LatticeKeys.ValueParameter,
                typeProvider = {
                  parameter.resolvedReturnType.withArguments(it.mapToArray { it.toConeType() })
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
        LatticeSymbols.Names.invoke -> {
          // Companion object invoke function, i.e. no creator
          check(graphObject.creator == null)
          val generatedFunction =
            createMemberFunction(
              owner,
              LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration,
              callableId.callableName,
              returnTypeProvider = { graphClass.constructType(it.mapToArray { it.toConeType() }) },
            ) {
              status { isOperator = true }
            }
          return listOf(generatedFunction.symbol)
        }
        LatticeSymbols.Names.factoryFunctionName -> {
          // Companion object factory function, i.e. factory()
          val creatorClass = graphObject.creator!!.classSymbol
          val generatedFunction =
            createMemberFunction(
              owner,
              LatticeKeys.LatticeGraphFactoryCompanionGetter,
              callableId.callableName,
              returnTypeProvider = { creatorClass.constructType(it.mapToArray { it.toConeType() }) },
            )
          return listOf(generatedFunction.symbol)
        }
        else -> {
          // It's an interface creator, generate the SAM function
          val creator = checkNotNull(graphObject.creator)
          return creator.function?.let { listOf(generateSAMFunction(graphObject.classSymbol, it)) }
            ?: emptyList<FirNamedFunctionSymbol>()
        }
      }
    }

    // Graph factory $$Impl class, just generate the SAM function
    if (owner.hasOrigin(LatticeKeys.LatticeGraphFactoryImplDeclaration)) {
      val graphObject = graphObjects.getValue(owner.requireContainingClassSymbol().classId)
      val creator = graphObject.creator!!
      return creator.function?.let { listOf(generateSAMFunction(graphObject.classSymbol, it)) }
        ?: emptyList()
    }

    // LatticeGraph class, implement injectors and accessors
    if (owner.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
      val graphObject = graphObjects.getValue(owner.requireContainingClassSymbol().classId)
      val functions = mutableListOf<FirNamedFunctionSymbol>()
      if (callableId.callableName in graphObject.bindsOrAccessors) {
        val function = graphObject.bindsOrAccessors[callableId.callableName]
        if (function !is FirNamedFunctionSymbol) return emptyList()
        // Simple accessor function
        // Need to copy over any qualifiers or @Binds!
        functions +=
          createMemberFunction(
              owner,
              key = LatticeKeys.LatticeGraphAccessorCallableOverride,
              returnTypeProvider = { function.resolvedReturnType },
              name = callableId.callableName,
            ) {
              status { isOverride = true }

              function.receiverParameter?.let { receiver ->
                extensionReceiverType(receiver.typeRef.coneType)
              }

              // Will only have parameters if it's binds. FIR checker will enforce this
              for (parameter in function.valueParameterSymbols) {
                valueParameter(
                  name = parameter.name,
                  key = LatticeKeys.ValueParameter,
                  typeProvider = {
                    parameter.resolvedReturnType.withArguments(it.mapToArray { it.toConeType() })
                  },
                )
              }
            }
            .apply { replaceAnnotations(function.annotations) }
            .symbol
      }

      // Note we don't do else-if because in theory you could have both
      //  fun inject(): Inject
      //  fun inject(somethingElse: SomethingElse)
      if (callableId.callableName in graphObject.injectors) {
        // Injector function
        val function = graphObject.injectors.getValue(callableId.callableName)
        // Simple accessor function
        // TODO switch back to createMemberFunction
        functions +=
          generateMemberFunction(
              owner = owner,
              returnTypeRef = session.builtinTypes.unitType,
              origin = LatticeKeys.LatticeGraphInjectorCallableOverride.origin,
              callableId = callableId,
            ) {
              status = status.copy(isOverride = true)

              function.receiverParameter?.let { receiver ->
                receiverParameter = buildReceiverParameter { typeRef = receiver.typeRef }
              }

              val paramsToCopy =
                function.valueParameterSymbols.map { LatticeFirValueParameter(session, it) }
              copyParameters(this, paramsToCopy, false, {})
            }
            .symbol
      }

      return functions
    }

    return emptyList()
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    if (owner.hasOrigin(LatticeKeys.LatticeGraphDeclaration)) {
      val graphObject = graphObjects.getValue(owner.requireContainingClassSymbol().classId)
      val properties = mutableListOf<FirPropertySymbol>()
      if (callableId.callableName in graphObject.bindsOrAccessors) {
        val property = graphObject.bindsOrAccessors.getValue(callableId.callableName)
        if (property !is FirPropertySymbol) return emptyList()
        // Simple accessor property
        properties +=
          createMemberProperty(
              owner,
              key = LatticeKeys.LatticeGraphAccessorCallableOverride,
              name = callableId.callableName,
              returnTypeProvider = { property.resolvedReturnType },
            ) {
              status { isOverride = true }
              property.receiverParameter?.let { extensionReceiverType(it.typeRef.coneType) }
            }
            .apply {
              replaceAnnotations(property.annotations)
              getter?.replaceAnnotations(property.getterSymbol?.annotations.orEmpty())
              setter?.replaceAnnotations(property.setterSymbol?.annotations.orEmpty())
              backingField?.replaceAnnotations(property.backingFieldSymbol?.annotations.orEmpty())
            }
            .symbol
      }
      return properties
    }

    return emptyList()
  }
}
