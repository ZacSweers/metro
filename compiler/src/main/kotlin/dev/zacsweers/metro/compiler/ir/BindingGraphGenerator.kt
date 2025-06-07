// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.transformers.InjectConstructorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

/**
 * Generates an [IrBindingGraph] for the given [node]. This only constructs the graph from available
 * bindings and does _not_ validate it.
 */
internal class BindingGraphGenerator(
  metroContext: IrMetroContext,
  private val node: DependencyGraphNode,
  // TODO preprocess these instead and just lookup via irAttribute
  private val injectConstructorTransformer: InjectConstructorTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
) : IrMetroContext by metroContext {
  fun generate(): IrBindingGraph {
    val graph =
      IrBindingGraph(
        this,
        node,
        newBindingStack = {
          IrBindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.BindingGraphConstruction))
        },
        findClassFactory = { clazz ->
          injectConstructorTransformer.getOrGenerateFactory(
            clazz,
            previouslyFoundConstructor = null,
            doNotErrorOnMissing = true,
          )
        },
        findMemberInjectors = membersInjectorTransformer::getOrGenerateAllInjectorsFor,
      )

    // Add explicit bindings from @Provides methods
    val bindingStack =
      IrBindingStack(
        node.sourceGraph,
        metroContext.loggerFor(MetroLogger.Type.BindingGraphConstruction),
      )

    // Add instance parameters
    val graphInstanceBinding =
      Binding.BoundInstance(
        node.typeKey,
        "${node.sourceGraph.name}Provider",
        node.sourceGraph.location(),
      )
    graph.addBinding(node.typeKey, graphInstanceBinding, bindingStack)

    // Mapping of supertypes to aliased bindings
    // We populate this for the current graph type first and then
    // add to them when processing extended parent graphs IFF there
    // is not already an existing entry. We do it this way to handle
    // cases where both the child graph and parent graph implement
    // a shared interface. In this scenario, the child alias wins
    // and we do not need to try to add another (duplicate) binding
    val superTypeToAlias = mutableMapOf<IrTypeKey, IrTypeKey>()

    // Add aliases for all its supertypes
    // TODO dedupe supertype iteration
    for (superType in node.supertypes) {
      val superTypeKey = IrTypeKey(superType)
      superTypeToAlias.putIfAbsent(superTypeKey, node.typeKey)
    }

    val providerFactoriesToAdd = buildList {
      addAll(node.providerFactories)
      addAll(
        node.allExtendedNodes.flatMap { (_, extendedNode) ->
          extendedNode.providerFactories.filterNot {
            // Do not include scoped providers as these should _only_ come from this graph
            // instance
            it.second.annotations.isScoped
          }
        }
      )
    }

    providerFactoriesToAdd.forEach { (typeKey, providerFactory) ->
      // Track a lookup of the provider class for IC
      trackClassLookup(node.sourceGraph, providerFactory.clazz)
      trackFunctionCall(node.sourceGraph, providerFactory.mirrorFunction)

      val contextKey =
        if (providerFactory.annotations.isIntoMultibinding) {
          IrContextualTypeKey.create(
            typeKey.transformMultiboundQualifier(metroContext, providerFactory.annotations)
          )
        } else {
          IrContextualTypeKey.create(typeKey)
        }
      val provider =
        Binding.Provided(
          providerFactory = providerFactory,
          contextualTypeKey = contextKey,
          parameters = providerFactory.parameters,
          annotations = providerFactory.annotations,
        )

      if (provider.isIntoMultibinding) {
        val originalQualifier = providerFactory.function.qualifierAnnotation()
        graph
          .getOrCreateMultibinding(
            pluginContext = pluginContext,
            annotations = providerFactory.annotations,
            contextKey = contextKey,
            declaration = providerFactory.function,
            originalQualifier = originalQualifier,
            bindingStack = bindingStack,
          )
          .sourceBindings
          .add(contextKey.typeKey)
      }

      graph.addBinding(contextKey.typeKey, provider, bindingStack)
    }

    // Add aliases ("@Binds")
    val bindsFunctionsToAdd = buildList {
      addAll(node.bindsFunctions)
      // Exclude scoped Binds, those will be exposed via provider field accessor
      addAll(node.allExtendedNodes.values.filter { it.isExtendable }.flatMap { it.bindsFunctions })
    }
    bindsFunctionsToAdd.forEach { (bindingCallable, initialContextKey) ->
      val annotations = bindingCallable.annotations
      val parameters = bindingCallable.ir.parameters(metroContext)
      // TODO what about T -> T but into multibinding
      val bindsImplType =
        if (annotations.isBinds) {
          parameters.extensionOrFirstParameter?.contextualTypeKey
            ?: error(
              "Missing receiver parameter for @Binds function: ${bindingCallable.ir.dumpKotlinLike()} in class ${bindingCallable.ir.parentAsClass.classId}"
            )
        } else {
          null
        }

      val contextKey =
        if (annotations.isIntoMultibinding) {
          IrContextualTypeKey.create(
            initialContextKey.typeKey.transformMultiboundQualifier(metroContext, annotations)
          )
        } else {
          initialContextKey
        }

      val binding =
        Binding.Alias(
          contextKey.typeKey,
          bindsImplType!!.typeKey,
          bindingCallable.ir,
          parameters,
          annotations,
        )

      if (annotations.isIntoMultibinding) {
        graph
          .getOrCreateMultibinding(
            pluginContext,
            annotations,
            contextKey,
            bindingCallable.ir,
            annotations.qualifier,
            bindingStack,
          )
          .sourceBindings
          .add(binding.typeKey)
      }

      graph.addBinding(binding.typeKey, binding, bindingStack)
    }

    node.creator?.parameters?.regularParameters.orEmpty().forEach { creatorParam ->
      // Only expose the binding if it's a bound instance or extended graph. Included containers are
      // not directly available
      if (creatorParam.isBindsInstance || creatorParam.isExtends) {
        val paramTypeKey = creatorParam.typeKey
        graph.addBinding(
          paramTypeKey,
          Binding.BoundInstance(creatorParam, creatorParam.ir.location()),
          bindingStack,
        )
      }
    }

    // Traverse all parent graph supertypes to create binding aliases as needed
    node.allExtendedNodes.forEach { (typeKey, extendedNode) ->
      // If it's a contributed graph, add an alias for the parent types since that's what
      // bindings will look for. i.e. $$ContributedLoggedInGraph -> LoggedInGraph + supertypes
      for (superType in extendedNode.supertypes) {
        val parentTypeKey = IrTypeKey(superType)

        // Ignore the graph declaration itself, handled separately
        if (parentTypeKey == typeKey) continue

        superTypeToAlias.putIfAbsent(parentTypeKey, typeKey)
      }
    }

    // Now that we've processed all supertypes
    superTypeToAlias.forEach { (superTypeKey, aliasedType) ->
      // We may have already added a `@Binds` declaration explicitly, this is ok!
      // TODO warning?
      if (superTypeKey !in graph) {
        graph.addBinding(
          superTypeKey,
          Binding.Alias(
            superTypeKey,
            aliasedType,
            null,
            Parameters.empty(),
            MetroAnnotations.none(),
          ),
          bindingStack,
        )
      }
    }

    val accessorsToAdd = buildList {
      addAll(node.accessors)
      addAll(
        node.allExtendedNodes.flatMap { (_, extendedNode) ->
          // Pass down @Multibinds declarations in the same way we do for multibinding providers
          extendedNode.accessors.filter { it.first.annotations.isMultibinds }
        }
      )
    }

    accessorsToAdd.forEach { (getter, contextualTypeKey) ->
      val multibinds = getter.annotations.multibinds
      val isMultibindingDeclaration = multibinds != null

      if (isMultibindingDeclaration) {
        if (contextualTypeKey.typeKey !in graph) {
          val multibinding =
            Binding.Multibinding.fromMultibindsDeclaration(
              metroContext,
              getter,
              multibinds,
              contextualTypeKey,
            )
          graph.addBinding(contextualTypeKey.typeKey, multibinding, bindingStack)
        } else {
          // If it's already in the graph, ensure its allowEmpty is up to date and update its
          // location
          val allowEmpty = multibinds.ir.getSingleConstBooleanArgumentOrNull() ?: false
          graph
            .requireBinding(contextualTypeKey.typeKey, bindingStack)
            .expectAs<Binding.Multibinding>()
            .let {
              it.allowEmpty = allowEmpty
              it.declaration = getter.ir
            }
        }
      } else {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
      }
    }

    // Add bindings from graph dependencies
    // TODO dedupe this allDependencies iteration with graph gen
    // TODO try to make accessors in this single-pass
    node.allIncludedNodes.forEach { depNode ->
      val accessorNames =
        depNode.proto?.provider_field_names?.toSet().orEmpty() +
          depNode.proto?.instance_field_names?.toSet().orEmpty()
      // Only add accessors for included types. If they're an accessor to a scoped provider, they
      // will be handled by the provider field accessor later
      for ((getter, contextualTypeKey) in depNode.accessors) {
        val name = getter.ir.name.asString()
        if (name.removeSuffix(Symbols.StringNames.METRO_ACCESSOR) in accessorNames) {
          // We'll handle this farther down
          continue
        }
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.GraphDependency(
            graph = depNode.sourceGraph,
            getter = getter.ir,
            isProviderFieldAccessor = false,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
        // Record a lookup for IC
        trackFunctionCall(node.sourceGraph, getter.ir)
      }
    }

    node.allExtendedNodes.forEach { (_, depNode) ->
      if (depNode.isExtendable) {
        depNode.proto?.let { proto ->
          val providerFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()
          val instanceFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()

          val providerFieldsSet = proto.provider_field_names.toSet()
          val instanceFieldsSet = proto.instance_field_names.toSet()

          val graphImpl = depNode.sourceGraph.metroGraphOrFail
          for (accessor in graphImpl.functions) {
            // Exclude toString/equals/hashCode or use marker annotation?
            if (accessor.isInheritedFromAny(pluginContext.irBuiltIns)) {
              continue
            }
            when (accessor.name.asString().removeSuffix(Symbols.StringNames.METRO_ACCESSOR)) {
              in providerFieldsSet -> {
                val metroFunction = metroFunctionOf(accessor)
                providerFieldAccessorsByName[metroFunction.ir.name] = metroFunction
              }

              in instanceFieldsSet -> {
                val metroFunction = metroFunctionOf(accessor)
                instanceFieldAccessorsByName[metroFunction.ir.name] = metroFunction
              }
            }
          }

          proto.provider_field_names.forEach { providerField ->
            val accessor =
              providerFieldAccessorsByName.getValue(
                "${providerField}${Symbols.StringNames.METRO_ACCESSOR}".asName()
              )
            val contextualTypeKey = IrContextualTypeKey.from(this, accessor.ir)
            val existingBinding = graph.findBinding(contextualTypeKey.typeKey)
            if (existingBinding != null) {
              // If it's a graph type we can just proceed, can happen with common ancestors
              val rawType = existingBinding.typeKey.type.rawTypeOrNull()
              if (rawType?.annotationsIn(symbols.dependencyGraphAnnotations).orEmpty().any()) {
                return@forEach
              }
            }
            graph.addBinding(
              contextualTypeKey.typeKey,
              Binding.GraphDependency(
                graph = depNode.sourceGraph,
                getter = accessor.ir,
                isProviderFieldAccessor = true,
                typeKey = contextualTypeKey.typeKey,
              ),
              bindingStack,
            )
            // Record a lookup for IC
            trackFunctionCall(node.sourceGraph, accessor.ir)
          }

          proto.instance_field_names.forEach { instanceField ->
            val accessor =
              instanceFieldAccessorsByName.getValue(
                "${instanceField}${Symbols.StringNames.METRO_ACCESSOR}".asName()
              )
            val contextualTypeKey = IrContextualTypeKey.from(this, accessor.ir)
            val existingBinding = graph.findBinding(contextualTypeKey.typeKey)
            if (existingBinding != null) {
              // If it's a graph type we can just proceed, can happen with common ancestors
              val rawType = existingBinding.typeKey.type.rawTypeOrNull()
              if (rawType?.annotationsIn(symbols.dependencyGraphAnnotations).orEmpty().any()) {
                return@forEach
              }
            }
            graph.addBinding(
              contextualTypeKey.typeKey,
              Binding.GraphDependency(
                graph = depNode.sourceGraph,
                getter = accessor.ir,
                isProviderFieldAccessor = true,
                typeKey = contextualTypeKey.typeKey,
              ),
              bindingStack,
            )
            // Record a lookup for IC
            trackFunctionCall(node.sourceGraph, accessor.ir)
          }
        }
      }
    }

    // Add MembersInjector bindings defined on injector functions
    node.injectors.forEach { (injector, typeKey) ->
      val contextKey = IrContextualTypeKey(typeKey)
      val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)

      graph.addInjector(typeKey, entry)
      bindingStack.withEntry(entry) {
        val targetClass = injector.ir.regularParameters.single().type.rawType()
        // Don't return null on missing because it's legal to inject a class with no member
        // injections
        // TODO warn on this?
        val generatedInjector = membersInjectorTransformer.getOrGenerateInjector(targetClass)
        // Create a remapper for the target class type parameters
        val paramType = injector.ir.regularParameters.single().type
        val substitutionMap = targetClass.buildSubstitutionMapFor(paramType)
        val remapper = typeRemapperFor(substitutionMap)
        val params = generatedInjector?.mergedParameters(remapper) ?: Parameters.empty()

        // Remap the parameters using the remapper
        val remappedParams =
          params.let { p ->
            if (p.ir != null) {
              p.ir.parameters(this, remapper).withCallableId(injector.callableId)
            } else {
              p.withCallableId(injector.callableId)
            }
          }

        val binding =
          Binding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters = remappedParams,
            reportableLocation = injector.ir.location(),
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        graph.addBinding(typeKey, binding, bindingStack)
      }
    }

    // Don't eagerly create bindings for injectable types, they'll be created on-demand
    // when dependencies are analyzed
    // TODO collect unused bindings?

    return graph
  }
}
