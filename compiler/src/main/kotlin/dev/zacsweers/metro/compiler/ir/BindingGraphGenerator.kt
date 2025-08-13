// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.transformers.InjectConstructorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor

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
  private val contributionData: IrContributionData,
  private val parentContext: ParentContext?,
) : IrMetroContext by metroContext {
  fun generate(): IrBindingGraph {
    val bindingLookup =
      BindingLookup(
        metroContext = metroContext,
        sourceGraph = node.sourceGraph,
        findClassFactory = { clazz ->
          injectConstructorTransformer.getOrGenerateFactory(
            clazz,
            previouslyFoundConstructor = null,
            doNotErrorOnMissing = true,
          )
        },
        findMemberInjectors = membersInjectorTransformer::getOrGenerateAllInjectorsFor,
        parentContext = parentContext,
      )

    val graph =
      IrBindingGraph(
        this,
        node,
        newBindingStack = {
          IrBindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.BindingGraphConstruction))
        },
        bindingLookup = bindingLookup,
      )

    // Add explicit bindings from @Provides methods
    val bindingStack =
      IrBindingStack(
        node.sourceGraph,
        metroContext.loggerFor(MetroLogger.Type.BindingGraphConstruction),
      )

    // Add instance parameters
    val graphInstanceBinding =
      IrBinding.BoundInstance(node.typeKey, "${node.sourceGraph.name}Provider", node.sourceGraph)
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

    val inheritedProviderFactories =
      node.allExtendedNodes
        .flatMap { (_, extendedNode) ->
          extendedNode.providerFactories.filterNot {
            // Do not include scoped providers as these should _only_ come from this graph
            // instance
            it.second.annotations.isScoped
          }
        }
        .associateBy { it.second }

    // Track IC lookups for binds callables but don't add bindings yet - they'll be added lazily
    val inheritedBindsCallables = node.allExtendedNodes.values.flatMapToSet { it.bindsCallables }

    val providerFactoriesToAdd = buildList {
      addAll(node.providerFactories)
      addAll(inheritedProviderFactories.values)
    }

    for ((typeKey, providerFactory) in providerFactoriesToAdd) {
      // Track IC lookups for provider factories but don't add bindings yet - they'll be added
      // lazily
      trackClassLookup(node.sourceGraph, providerFactory.clazz)
      trackFunctionCall(node.sourceGraph, providerFactory.mirrorFunction)
      trackFunctionCall(node.sourceGraph, providerFactory.function)

      if (
        !providerFactory.annotations.isIntoMultibinding &&
          typeKey in graph &&
          providerFactory in inheritedProviderFactories
      ) {
        // If we already have a binding provisioned in this scenario, ignore the parent's version
        continue
      }

      val targetTypeKey =
        if (providerFactory.annotations.isIntoMultibinding) {
          providerFactory.typeKey.transformMultiboundQualifier(providerFactory.annotations)
        } else {
          providerFactory.typeKey
        }
      val contextKey = IrContextualTypeKey(targetTypeKey)

      val binding =
        IrBinding.Provided(
          providerFactory = providerFactory,
          contextualTypeKey = contextKey,
          parameters = providerFactory.parameters,
          annotations = providerFactory.annotations,
        )

      // Check for duplicates before adding to cache
      // TODO aggregate duplicates and report all
      val existingProvider = bindingLookup.getStaticBinding(targetTypeKey) as? IrBinding.Provided
      if (existingProvider != null && existingProvider != binding) {
        // Check if the existing one is from an inherited graph
        val isExistingInherited = existingProvider.providerFactory in inheritedProviderFactories
        val isCurrentInherited = providerFactory in inheritedProviderFactories

        if (isExistingInherited && !isCurrentInherited) {
          // Current graph's binding replaces the inherited one
          bindingLookup.putBinding(binding)
        } else if (!isExistingInherited && isCurrentInherited) {
          // Current graph already has this binding, skip the inherited one
          // Do nothing - keep the existing binding
        } else {
          // Both are from the same level (both current or both inherited) - this is an error
          graph.reportDuplicateBinding(typeKey, existingProvider, binding, bindingStack)
        }
      } else if (existingProvider == null) {
        // Also check if there's already a provider factory for this key
        val existingAlias = bindingLookup.getStaticBinding(targetTypeKey) as? IrBinding.Alias
        if (existingAlias != null) {
          // Check if the existing provider is from an inherited graph
          val isAliasInherited = existingAlias.bindsCallable in inheritedBindsCallables
          val isCurrentInherited = providerFactory in inheritedProviderFactories

          if (isAliasInherited && !isCurrentInherited) {
            // Current graph's @Binds replaces the inherited @Provides
            bindingLookup.removeAliasBinding(targetTypeKey)
            bindingLookup.putBinding(binding)
          } else if (!isAliasInherited && isCurrentInherited) {
            // Current graph already has @Binds, skip the inherited @Provides
            // Do nothing - keep the existing provider factory
          } else {
            // Both are from the same level - this is an error
            graph.reportDuplicateBinding(targetTypeKey, existingAlias, binding, bindingStack)
          }
        } else {
          // Add to cache for O(1) lookups
          bindingLookup.putBinding(binding)
        }
      }

      // Handle multibinding setup (but don't add the binding itself)
      if (providerFactory.annotations.isIntoMultibinding) {
        val originalQualifier = providerFactory.function.qualifierAnnotation()
        graph
          .getOrCreateMultibinding(
            annotations = providerFactory.annotations,
            contextKey = contextKey,
            declaration = providerFactory.function,
            originalQualifier = originalQualifier,
            bindingStack = bindingStack,
          )
          .addSourceBinding(contextKey.typeKey)
      }

      if (options.enableStrictValidation) {
        graph.addBinding(binding.typeKey, binding, bindingStack)
      } else {
        // The actual binding will be added lazily via BindingLookup when needed
      }
    }

    val bindsFunctionsToAdd = buildList {
      addAll(node.bindsCallables)
      addAll(inheritedBindsCallables)
    }

    for (bindsCallable in bindsFunctionsToAdd) {
      // Track a lookup of the target for IC
      trackFunctionCall(node.sourceGraph, bindsCallable.function)
      trackFunctionCall(node.sourceGraph, bindsCallable.callableMetadata.mirrorFunction)
      trackClassLookup(node.sourceGraph, bindsCallable.function.parentAsClass)
      trackClassLookup(
        node.sourceGraph,
        bindsCallable.callableMetadata.mirrorFunction.parentAsClass,
      )

      if (
        !bindsCallable.callableMetadata.annotations.isIntoMultibinding &&
          bindsCallable.target in graph &&
          bindsCallable in inheritedBindsCallables
      ) {
        // If we already have a binding provisioned in this scenario, ignore the parent's version
        continue
      }

      val annotations = bindsCallable.callableMetadata.annotations
      val targetTypeKey = bindsCallable.target.transformMultiboundQualifier(annotations)
      val parameters = bindsCallable.function.parameters()
      val bindsImplType =
        parameters.extensionOrFirstParameter?.contextualTypeKey
          ?: error("Missing receiver parameter for @Binds function: ${bindsCallable.function}")

      val binding =
        IrBinding.Alias(
          typeKey = targetTypeKey,
          aliasedType = bindsImplType.typeKey,
          bindsCallable = bindsCallable,
          parameters = parameters,
        )

      val contextKey = binding.contextualTypeKey

      // Check for duplicates before adding to cache
      // TODO aggregate duplicates and report all
      val existingBinding = bindingLookup.getStaticBinding(targetTypeKey) as? IrBinding.Alias
      if (existingBinding != null && existingBinding.bindsCallable != bindsCallable) {
        // Check if the existing one is from an inherited graph
        val isExistingInherited = existingBinding.bindsCallable in inheritedBindsCallables
        val isCurrentInherited = bindsCallable in inheritedBindsCallables

        if (isExistingInherited && !isCurrentInherited) {
          // Current graph's binding replaces the inherited one
          bindingLookup.putBinding(binding)
        } else if (!isExistingInherited && isCurrentInherited) {
          // Current graph already has this binding, skip the inherited one
          // Do nothing - keep the existing binding
        } else {
          // Both are from the same level (both current or both inherited) - this is an error
          // TODO Could check if there's a duplicate from provider factories to better message
          graph.reportDuplicateBinding(targetTypeKey, existingBinding, binding, bindingStack)
        }
      } else if (existingBinding == null) {
        // Also check if there's already a provider factory for this key
        val existingProvider = bindingLookup.getStaticBinding(targetTypeKey) as? IrBinding.Provided
        if (existingProvider != null) {
          // Check if the existing provider is from an inherited graph
          val isProviderInherited = existingProvider.providerFactory in inheritedProviderFactories
          val isCurrentInherited = bindsCallable in inheritedBindsCallables

          if (isProviderInherited && !isCurrentInherited) {
            // Current graph's @Binds replaces the inherited @Provides
            bindingLookup.removeProvidedBinding(targetTypeKey)
            bindingLookup.putBinding(binding)
          } else if (!isProviderInherited && isCurrentInherited) {
            // Current graph already has @Provides, skip the inherited @Binds
            // Do nothing - keep the existing provider factory
          } else {
            // Both are from the same level - this is an error
            graph.reportDuplicateBinding(targetTypeKey, existingProvider, binding, bindingStack)
          }
        } else {
          // Add to cache for O(1) lookups
          bindingLookup.putBinding(binding)
        }
      }

      // Handle multibinding setup (but don't add the binding itself)
      if (annotations.isIntoMultibinding) {
        graph
          .getOrCreateMultibinding(
            annotations = annotations,
            contextKey = contextKey,
            declaration = bindsCallable.function,
            originalQualifier = annotations.qualifier,
            bindingStack = bindingStack,
          )
          .addSourceBinding(targetTypeKey)
      }

      if (options.enableStrictValidation) {
        val bindings =
          bindingLookup.lookup(
            contextKey,
            currentBindings = graph.bindingsSnapshot().keys,
            bindingStack,
          )
        for (binding in bindings) {
          graph.addBinding(binding.typeKey, binding, bindingStack)
        }
      } else {
        // The actual binding will be added lazily via BindingLookup when needed
      }
    }

    node.creator?.parameters?.regularParameters.orEmpty().forEach { creatorParam ->
      // Only expose the binding if it's a bound instance, extended graph, or target is annotated
      // @BindingContainer
      val shouldExposeBinding =
        creatorParam.isBindsInstance ||
          !creatorParam.isIncludes || // TODO this param should disappear. Is "@Extends"
          creatorParam.typeKey.type
            .rawTypeOrNull()
            ?.isAnnotatedWithAny(symbols.classIds.bindingContainerAnnotations) == true
      if (shouldExposeBinding) {
        val paramTypeKey = creatorParam.typeKey
        graph.addBinding(
          paramTypeKey,
          IrBinding.BoundInstance(creatorParam, creatorParam.ir),
          bindingStack,
        )
        val rawType = creatorParam.type.rawType()
        // Add the original type too as an alias
        val regularGraph = rawType.sourceGraphIfMetroGraph
        if (regularGraph != rawType) {
          val keyType = regularGraph.typeWith(creatorParam.type.expectAs<IrSimpleType>().arguments.map { it.typeOrFail })
          val typeKey = IrTypeKey(keyType)
          superTypeToAlias.putIfAbsent(typeKey, paramTypeKey)
        }
      }
    }

    val allManagedBindingContainerInstances = buildSet {
      addAll(node.bindingContainers)
      addAll(node.allExtendedNodes.values.flatMapToSet { it.bindingContainers })
    }
    for (it in allManagedBindingContainerInstances) {
      val typeKey = IrTypeKey(it)
      graph.addBinding(
        typeKey,
        IrBinding.BoundInstance(typeKey, it.name.asString(), it),
        bindingStack,
      )
    }

    fun addOrUpdateMultibinding(
      contextualTypeKey: IrContextualTypeKey,
      getter: IrSimpleFunction,
      multibinds: IrAnnotation,
    ) {
      if (contextualTypeKey.typeKey !in graph) {
        val multibinding =
          IrBinding.Multibinding.fromMultibindsDeclaration(getter, multibinds, contextualTypeKey)
        graph.addBinding(contextualTypeKey.typeKey, multibinding, bindingStack)
      } else {
        // If it's already in the graph, ensure its allowEmpty is up to date and update its
        // location
        graph
          .requireBinding(contextualTypeKey.typeKey, bindingStack)
          .expectAs<IrBinding.Multibinding>()
          .let {
            it.allowEmpty = multibinds.allowEmpty()
            it.declaration = getter
          }
      }

      // Record an IC lookup
      trackClassLookup(node.sourceGraph, getter.propertyIfAccessor.parentAsClass)
      trackFunctionCall(node.sourceGraph, getter)
    }

    val allMultibindsCallables = buildList {
      addAll(node.multibindsCallables)
      addAll(node.allExtendedNodes.values.flatMapToSet { it.multibindsCallables })
    }

    for (multibindsCallable in allMultibindsCallables) {
      val contextKey = IrContextualTypeKey(multibindsCallable.typeKey)
      addOrUpdateMultibinding(
        contextKey,
        multibindsCallable.callableMetadata.mirrorFunction,
        multibindsCallable.callableMetadata.annotations.multibinds!!,
      )

      // Record an IC lookup of the original function/class for good measure
      trackFunctionCall(node.sourceGraph, multibindsCallable.function)
      trackClassLookup(
        node.sourceGraph,
        multibindsCallable.function.propertyIfAccessor.parentAsClass,
      )
    }

    // Traverse all parent graph supertypes to create binding aliases as needed
    for ((typeKey, extendedNode) in node.allExtendedNodes) {
      // If it's a contributed graph, add an alias for the parent types since that's what
      // bindings will look for. i.e. $$ContributedLoggedInGraph -> LoggedInGraph + supertypes
      for (superType in extendedNode.supertypes) {
        val parentTypeKey = IrTypeKey(superType)

        // Ignore the graph declaration itself, handled separately
        if (parentTypeKey == typeKey) continue

        superTypeToAlias.putIfAbsent(parentTypeKey, typeKey)
      }
    }

    // Now that we've processed all supertypes/aliases
    for ((superTypeKey, aliasedType) in superTypeToAlias) {
      // We may have already added a `@Binds` declaration explicitly, this is ok!
      // TODO warning?
      if (superTypeKey !in graph) {
        graph.addBinding(
          superTypeKey,
          IrBinding.Alias(superTypeKey, aliasedType, null, Parameters.empty()),
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

    for ((getter, contextualTypeKey) in accessorsToAdd) {
      val multibinds = getter.annotations.multibinds
      val isMultibindingDeclaration = multibinds != null

      if (isMultibindingDeclaration) {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
        addOrUpdateMultibinding(contextualTypeKey, getter.ir, multibinds)
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
    // Only add it if it's a directly included node. Indirect will be propagated by metro
    // accessors
    for ((depNodeKey, depNode) in node.includedGraphNodes) {
      // Only add accessors for included types. If they're an accessor to a scoped provider, they
      // will be handled by the provider field accessor later
      for ((getter, contextualTypeKey) in depNode.accessors) {
        if (getter.annotations.isMetroAccessor) {
          // We'll handle this farther down
          continue
        }

        // Add a ref to the included graph if not already present
        if (depNodeKey !in graph) {
          graph.addBinding(
            depNodeKey,
            IrBinding.BoundInstance(
              depNodeKey,
              "${depNode.sourceGraph.name}Provider",
              depNode.sourceGraph,
            ),
            bindingStack,
          )
        }

        val irGetter = getter.ir
        val parentClass = irGetter.parentAsClass
        val parentName = parentClass.name
        val getterToUse =
          if (
            parentName == Symbols.Names.MetroGraph ||
              parentClass.origin == Origins.GeneratedGraphExtension ||
              parentName.asString().startsWith(Symbols.StringNames.CONTRIBUTED_GRAPH_PREFIX)
          ) {
            // Use the original graph decl so we don't tie this invocation to `$$MetroGraph`
            // specifically
            irGetter.overriddenSymbolsSequence().firstOrNull()?.owner
              ?: run { reportCompilerBug("${irGetter.dumpKotlinLike()} overrides nothing") }
          } else {
            irGetter
          }

        graph.addBinding(
          contextualTypeKey.typeKey,
          IrBinding.GraphDependency(
            ownerKey = depNodeKey,
            graph = depNode.sourceGraph,
            getter = getterToUse,
            isProviderFieldAccessor = false,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
        // Record a lookup for IC
        trackFunctionCall(node.sourceGraph, irGetter)
        trackFunctionCall(node.sourceGraph, getterToUse)
      }
    }

    // Add scoped accessors from directly known parent bindings
    // Only present if this is a contributed graph
    val isContributedGraph = node.sourceGraph.origin == Origins.GeneratedGraphExtension
    if (isContributedGraph) {
      if (parentContext == null) {
        reportCompilerBug("No parent bindings found for contributed graph ${node.sourceGraph.name}")
      }
      val directParent = node.extendedGraphNodes.values.first()
      val directParentClass =
        if (directParent.sourceGraph.origin == Origins.GeneratedGraphExtension) {
          // Parent is also a contributed graph, so the class itself is the parent
          directParent.sourceGraph
        } else {
          directParent.sourceGraph.metroGraphOrFail
        }
      for (key in parentContext.availableKeys()) {
        val existingBinding = graph.findBinding(key)
        if (existingBinding != null) {
          // If we already have a binding provisioned in this scenario, ignore the parent's
          // version
          continue
        }

        val accessorFunction = key.toAccessorFunctionIn(directParentClass, wrapInProvider = true)

        // TODO dedupe with below?
        graph.addBinding(
          key,
          IrBinding.GraphDependency(
            ownerKey = directParent.typeKey,
            graph = node.sourceGraph,
            getter = accessorFunction,
            isProviderFieldAccessor = true,
            typeKey = key,
          ),
          bindingStack,
        )
        // Record a lookup for IC
        trackFunctionCall(node.sourceGraph, accessorFunction)
      }
    } else {
      for (depNode in node.allExtendedNodes.values) {
        val graphImpl = depNode.sourceGraph.metroGraphOrFail
        for (accessor in graphImpl.functions) {
          // Exclude toString/equals/hashCode or use marker annotation?
          if (accessor.isInheritedFromAny(pluginContext.irBuiltIns)) {
            continue
          }

          val annotations = accessor.metroAnnotations(symbols.classIds)
          if (!annotations.isMetroAccessor) continue

          val metroFunction = metroFunctionOf(accessor, annotations)
          val contextualTypeKey = IrContextualTypeKey.from(metroFunction.ir)

          if (
            contextualTypeKey.typeKey == node.originalTypeKey ||
              contextualTypeKey.typeKey == node.creator?.typeKey
          ) {
            // Accessor of this graph extension or its factory, no need to include these
            continue
          }

          val existingBinding = graph.findBinding(contextualTypeKey.typeKey)
          if (existingBinding != null) {
            // If we already have a binding provisioned in this scenario, ignore the parent's
            // version
            continue
          }
          graph.addBinding(
            contextualTypeKey.typeKey,
            IrBinding.GraphDependency(
              ownerKey = depNode.typeKey,
              graph = depNode.sourceGraph,
              getter = metroFunction.ir,
              isProviderFieldAccessor = true,
              typeKey = contextualTypeKey.typeKey,
            ),
            bindingStack,
          )
          // Record a lookup for IC
          trackFunctionCall(node.sourceGraph, metroFunction.ir)
        }
      }
    }

    // Add MembersInjector bindings defined on injector functions
    for ((injector, contextKey) in node.injectors) {
      val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)

      graph.addInjector(contextKey, entry)
      bindingStack.withEntry(entry) {
        val param = injector.ir.regularParameters.single()
        val paramType = param.type
        val targetClass = paramType.rawType()
        // Don't return null on missing because it's legal to inject a class with no member
        // injections
        // TODO warn on this?
        val generatedInjector = membersInjectorTransformer.getOrGenerateInjector(targetClass)

        val remappedParams =
          if (targetClass.typeParameters.isEmpty()) {
              generatedInjector?.mergedParameters(NOOP_TYPE_REMAPPER)
            } else {
              // Create a remapper for the target class type parameters
              val remapper = targetClass.deepRemapperFor(paramType)
              val params = generatedInjector?.mergedParameters(remapper)
              params?.ir?.parameters(remapper) ?: params
            }
            .let { it ?: Parameters.empty() }
            .withCallableId(injector.callableId)

        val binding =
          IrBinding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters = remappedParams,
            reportableDeclaration = injector.ir,
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        graph.addBinding(contextKey.typeKey, binding, bindingStack)

        // Ensure that we traverse the target class's superclasses and lookup relevant bindings for
        // them too, namely ancestor member injectors
        val extraBindings =
          bindingLookup.lookup(
            IrContextualTypeKey.from(param),
            graph.bindingsSnapshot().keys,
            bindingStack,
          )
        for (extraBinding in extraBindings) {
          graph.addBinding(extraBinding.typeKey, extraBinding, bindingStack)
        }
      }
    }

    // Add bindings for scoped @Inject classes which don't have contributions
    // TODO can we remove this and move into ParentContext logic? TL;DR lookup of classes
    // with mismatched scopes
    node.scopes.flatMap(contributionData::getScopedInjectClasses).forEach { scopedClassTypeKey ->
      if (scopedClassTypeKey !in graph) {
        val contextKey = IrContextualTypeKey.create(scopedClassTypeKey)
        val bindings =
          bindingLookup.lookup(contextKey, graph.bindingsSnapshot().keys, IrBindingStack.empty())
        for (binding in bindings) {
          graph.addBinding(scopedClassTypeKey, binding, IrBindingStack.empty())
          // Mark this to be explicitly kept even after pruning unused
          // TODO this entry isn't really helpful
          graph.keep(contextKey, IrBindingStack.Entry.simpleTypeRef(contextKey))
        }
      }
    }

    return graph
  }
}
