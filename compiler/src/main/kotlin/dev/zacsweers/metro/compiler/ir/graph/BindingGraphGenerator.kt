// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.BindsLikeCallable
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.graph.expressions.IrOptionalExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.optionalType
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.qualifierAnnotation
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.trackMemberDeclarationCall
import dev.zacsweers.metro.compiler.ir.transformers.InjectConstructorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName
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

  private val ProviderFactory.isDynamic: Boolean
    get() = node.dynamicTypeKeys[typeKey] == this

  private val BindsLikeCallable.isDynamic: Boolean
    get() = node.dynamicTypeKeys[typeKey] == this

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
        contributionData = contributionData,
      )

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
          extendedNode.providerFactories.entries.filterNot { (_, factory) ->
            // Do not include scoped providers as these should _only_ come from this graph
            // instance
            factory.annotations.isScoped
          }
        }
        // Filter out inherited providers whose typeKey is already in the current node
        .filterNot { (typeKey, _) -> typeKey in node.providerFactories }
        .associate { it.toPair() }

    val inheritedBindsCallables =
      node.allExtendedNodes.values
        .flatMap { it.bindsCallables.entries }
        // Filter out inherited binds callables whose typeKey is already in the current node
        .filterNot { (typeKey, _) -> typeKey in node.bindsCallables }
        .associate { it.toPair() }

    val providerFactoriesToAdd = buildList {
      addAll(node.providerFactories.entries)
      addAll(inheritedProviderFactories.entries)
    }

    for ((typeKey, providerFactory) in providerFactoriesToAdd) {
      // Track IC lookups but don't add bindings yet - they'll be added lazily
      trackClassLookup(node.sourceGraph, providerFactory.factoryClass)
      trackFunctionCall(node.sourceGraph, providerFactory.function)
      if (providerFactory is ProviderFactory.Metro) {
        trackFunctionCall(node.sourceGraph, providerFactory.mirrorFunction)
      }

      if (
        !providerFactory.annotations.isIntoMultibinding &&
          typeKey in graph &&
          typeKey in inheritedProviderFactories
      ) {
        // If we already have a binding provisioned in this scenario, ignore the parent's version
        continue
      }

      // typeKey is already the transformed multibinding key
      val targetTypeKey = providerFactory.typeKey
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
      val isDynamic = providerFactory.isDynamic

      if (existingProvider != null && existingProvider != binding) {
        // Check if the existing one is from an inherited graph
        val isExistingInherited = existingProvider.typeKey in inheritedProviderFactories
        val isExistingDynamic = existingProvider.providerFactory.isDynamic
        val isCurrentInherited = typeKey in inheritedProviderFactories

        if (isDynamic || (isExistingInherited && !isCurrentInherited)) {
          // Current graph's binding replaces the inherited one
          bindingLookup.putBinding(binding)
        } else if (isExistingDynamic) {
          // Was already replaced with a dynamic one, don't overwrite it
          continue
        } else if (!isExistingInherited && isCurrentInherited) {
          // Current graph already has this binding, skip the inherited one
          // Do nothing - keep the existing binding
          continue
        } else {
          // Both are from the same level (both current or both inherited) - this is an error
          graph.reportDuplicateBinding(typeKey, existingProvider, binding, bindingStack)
          continue
        }
      } else if (existingProvider == null) {
        // Also check if there's already a provider factory for this key
        val existingAlias = bindingLookup.getStaticBinding(targetTypeKey) as? IrBinding.Alias
        if (existingAlias != null) {
          // Check if the existing provider is from an inherited graph
          val isAliasInherited = existingAlias.typeKey in inheritedBindsCallables
          val isAliasDynamic = existingAlias.bindsCallable?.isDynamic == true
          val isCurrentInherited = typeKey in inheritedProviderFactories

          if (isDynamic || (isAliasInherited && !isCurrentInherited)) {
            // Current graph's @Binds replaces the inherited @Provides
            bindingLookup.removeAliasBinding(targetTypeKey)
            bindingLookup.putBinding(binding)
          } else if (isAliasDynamic) {
            // Was already replaced with a dynamic one, don't overwrite it
            continue
          } else if (!isAliasInherited && isCurrentInherited) {
            // Current graph already has @Binds, skip the inherited @Provides
            // Do nothing - keep the existing provider factory
            continue
          } else {
            // Both are from the same level - this is an error
            graph.reportDuplicateBinding(targetTypeKey, existingAlias, binding, bindingStack)
            continue
          }
        } else {
          // Add to cache for O(1) lookups
          bindingLookup.putBinding(binding)
        }
      }

      if (options.enableFullBindingGraphValidation) {
        graph.addBinding(binding.typeKey, binding, bindingStack)
      } else {
        // The actual binding will be added lazily via BindingLookup when needed
      }
    }

    val bindsFunctionsToAdd = buildList {
      addAll(node.bindsCallables.values)
      addAll(inheritedBindsCallables.values)
    }

    for (bindsCallable in bindsFunctionsToAdd) {
      // Track IC lookups but don't add bindings yet - they'll be added lazily
      trackFunctionCall(node.sourceGraph, bindsCallable.function)
      trackFunctionCall(node.sourceGraph, bindsCallable.callableMetadata.mirrorFunction)
      trackClassLookup(node.sourceGraph, bindsCallable.function.parentAsClass)
      trackClassLookup(
        node.sourceGraph,
        bindsCallable.callableMetadata.mirrorFunction.parentAsClass,
      )

      if (
        !bindsCallable.callableMetadata.annotations.isIntoMultibinding &&
          bindsCallable.typeKey in graph &&
          bindsCallable.typeKey in inheritedBindsCallables
      ) {
        // If we already have a binding provisioned in this scenario, ignore the parent's version
        continue
      }

      val annotations = bindsCallable.callableMetadata.annotations
      // typeKey is already the transformed multibinding key
      val targetTypeKey = bindsCallable.typeKey
      val parameters = bindsCallable.function.parameters()
      val bindsImplType =
        parameters.extensionOrFirstParameter?.contextualTypeKey
          ?: reportCompilerBug(
            "Missing receiver parameter for @Binds function: ${bindsCallable.function}"
          )

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
      val isDynamic = bindsCallable.isDynamic

      if (existingBinding != null && existingBinding.bindsCallable != bindsCallable) {
        // Check if the existing one is from an inherited graph
        val isExistingInherited = existingBinding.typeKey in inheritedBindsCallables
        val isExistingDynamic = existingBinding.bindsCallable?.isDynamic == true
        val isCurrentInherited = targetTypeKey in inheritedBindsCallables

        if (isDynamic || (isExistingInherited && !isCurrentInherited)) {
          // Current graph's binding replaces the inherited one
          bindingLookup.putBinding(binding)
        } else if (isExistingDynamic) {
          // Was already replaced with a dynamic one, don't overwrite it
          continue
        } else if (!isExistingInherited && isCurrentInherited) {
          // Current graph already has this binding, skip the inherited one
          // Do nothing - keep the existing binding
          continue
        } else {
          // Both are from the same level (both current or both inherited) - this is an error
          // TODO Could check if there's a duplicate from provider factories to better message
          graph.reportDuplicateBinding(targetTypeKey, existingBinding, binding, bindingStack)
          continue
        }
      } else if (existingBinding == null) {
        // Also check if there's already a provider factory for this key
        val existingProvider = bindingLookup.getStaticBinding(targetTypeKey) as? IrBinding.Provided
        if (existingProvider != null) {
          // Check if the existing provider is from an inherited graph
          val isProviderInherited = existingProvider.typeKey in inheritedProviderFactories
          val isProviderDynamic = existingProvider.providerFactory.isDynamic
          val isCurrentInherited = targetTypeKey in inheritedBindsCallables

          if (isDynamic || (isProviderInherited && !isCurrentInherited)) {
            // Current graph's @Binds replaces the inherited @Provides
            bindingLookup.removeProvidedBinding(targetTypeKey)
            bindingLookup.putBinding(binding)
          } else if (isProviderDynamic) {
            // Was already replaced with a dynamic one, don't overwrite it
            continue
          } else if (!isProviderInherited && isCurrentInherited) {
            // Current graph already has @Provides, skip the inherited @Binds
            // Do nothing - keep the existing provider factory
            continue
          } else {
            // Both are from the same level - this is an error
            graph.reportDuplicateBinding(targetTypeKey, existingProvider, binding, bindingStack)
            continue
          }
        } else {
          // Add to cache for O(1) lookups
          bindingLookup.putBinding(binding)
        }
      }

      if (options.enableFullBindingGraphValidation) {
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
      // Only expose the binding if it's a bound instance, extended graph, or target is a binding
      // container
      val shouldExposeBinding =
        creatorParam.isBindsInstance ||
          with(this@BindingGraphGenerator) {
            creatorParam.typeKey.type.rawTypeOrNull()?.isBindingContainer() == true
          }
      if (shouldExposeBinding) {
        val paramTypeKey = creatorParam.typeKey

        // Check if there's a dynamic replacement for this bound instance
        val hasDynamicReplacement = paramTypeKey in node.dynamicTypeKeys
        val isDynamic = creatorParam.ir?.origin == Origins.DynamicContainerParam

        if (isDynamic || !hasDynamicReplacement) {
          // Only add the bound instance if there's no dynamic replacement
          graph.addBinding(
            paramTypeKey,
            IrBinding.BoundInstance(creatorParam, creatorParam.ir!!),
            bindingStack,
          )

          val rawType = creatorParam.type.rawType()
          // Add the original type too as an alias
          val regularGraph = rawType.sourceGraphIfMetroGraph
          if (regularGraph != rawType) {
            val keyType =
              regularGraph.symbol.typeWithArguments(
                creatorParam.type.requireSimpleType(creatorParam.ir).arguments
              )
            val typeKey = IrTypeKey(keyType)
            superTypeToAlias.putIfAbsent(typeKey, paramTypeKey)
          }
        }
      }
    }

    val allManagedBindingContainerInstances = buildSet {
      addAll(node.bindingContainers)
      addAll(node.allExtendedNodes.values.flatMapToSet { it.bindingContainers })
    }
    for (it in allManagedBindingContainerInstances) {
      val typeKey = IrTypeKey(it)

      val hasDynamicReplacement = typeKey in node.dynamicTypeKeys

      if (!hasDynamicReplacement) {
        // Only add the bound instance if there's no dynamic replacement
        graph.addBinding(
          typeKey,
          IrBinding.BoundInstance(typeKey, it.name.asString(), it),
          bindingStack,
        )
      }
    }

    fun registerMultibindsDeclaration(
      contextualTypeKey: IrContextualTypeKey,
      getter: IrSimpleFunction,
      multibinds: IrAnnotation,
    ) {
      // Register the @Multibinds declaration for lazy creation
      bindingLookup.registerMultibindsDeclaration(contextualTypeKey.typeKey, getter, multibinds)

      // Record an IC lookup
      trackClassLookup(node.sourceGraph, getter.propertyIfAccessor.parentAsClass)
      trackFunctionCall(node.sourceGraph, getter)
    }

    val allMultibindsCallables = buildList {
      addAll(node.multibindsCallables)
      addAll(node.allExtendedNodes.values.flatMapToSet { it.multibindsCallables })
    }

    for (multibindsCallable in allMultibindsCallables) {
      // Track IC lookups but don't add bindings yet - they'll be added lazily
      trackFunctionCall(node.sourceGraph, multibindsCallable.function)
      trackClassLookup(
        node.sourceGraph,
        multibindsCallable.function.propertyIfAccessor.parentAsClass,
      )

      val contextKey = IrContextualTypeKey(multibindsCallable.typeKey)
      registerMultibindsDeclaration(
        contextKey,
        multibindsCallable.callableMetadata.mirrorFunction,
        multibindsCallable.callableMetadata.annotations.multibinds!!,
      )
    }

    val allOptionalKeys = buildMap {
      putAll(node.optionalKeys)
      for ((_, extendedNode) in node.allExtendedNodes) {
        putAll(extendedNode.optionalKeys)
      }
    }

    for ((optionalKey, callables) in allOptionalKeys) {
      val declaration = callables.first().function
      val type =
        optionalKey.type.optionalType(declaration)
          ?: reportCompilerBug(
            "Optional type not supported: ${optionalKey.type.rawType().classIdOrFail.asSingleFqName()}"
          )

      // Construct a valid context key for this case
      val contextKey =
        type.asContextualTypeKey(
          qualifierAnnotation = optionalKey.qualifier,
          // Optionals have default behavior
          hasDefault = true,
          patchMutableCollections = true,
          declaration = null,
        )

      val binding =
        IrBinding.CustomWrapper(
          typeKey = optionalKey,
          wrapperKey = IrOptionalExpressionGenerator.key,
          allowsAbsent = true,
          declaration = declaration,
          wrappedType = type,
          wrappedContextKey = contextKey,
        )

      graph.addBinding(optionalKey, binding, bindingStack)
    }

    // Traverse all parent graph supertypes to create binding aliases as needed
    // TODO since this is processed with the parent, is it still needed?
    for ((typeKey, extendedNode) in node.allExtendedNodes) {
      // If it's a contributed graph, add an alias for the parent types since that's what
      // bindings will look for. i.e. LoggedInGraphImpl -> LoggedInGraph + supertypes
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
          extendedNode.accessors.filter { it.metroFunction.annotations.isMultibinds }
        }
      )
    }

    for ((contextualTypeKey, getter, _) in accessorsToAdd) {
      val multibinds = getter.annotations.multibinds
      val isMultibindingDeclaration = multibinds != null

      if (isMultibindingDeclaration) {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
        registerMultibindsDeclaration(contextualTypeKey, getter.ir, multibinds)
      } else {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
      }
    }

    for ((key, accessors) in node.graphExtensions) {
      for (accessor in accessors) {
        val shouldAddBinding =
          accessor.isFactory &&
            // It's allowed to specify multiple accessors for the same factory
            accessor.key.typeKey !in graph &&
            // Don't add a binding if the graph itself implements the factory
            accessor.key.typeKey.classId !in node.supertypeClassIds

        if (shouldAddBinding) {
          graph.addBinding(
            accessor.key.typeKey,
            IrBinding.GraphExtensionFactory(
              typeKey = accessor.key.typeKey,
              extensionTypeKey = key,
              parent = node.metroGraph!!,
              parentKey = node.typeKey,
              accessor = accessor.accessor.ir,
            ),
            bindingStack,
          )
        }
      }
    }

    // Add bindings from graph dependencies
    // TODO dedupe this allDependencies iteration with graph gen
    // TODO try to make accessors in this single-pass
    // Only add it if it's a directly included node. Indirect will be propagated by metro
    // accessors
    for ((depNodeKey, depNode) in node.includedGraphNodes) {
      // Only add accessors for included types
      for ((contextualTypeKey, getter, _) in depNode.accessors) {
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
        val getterToUse =
          if (
            irGetter.overriddenSymbols.isNotEmpty() &&
              parentClass.sourceGraphIfMetroGraph != parentClass
          ) {
            // Use the original graph decl so we don't tie this invocation to any impls
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
    val isGraphExtension = node.sourceGraph.origin == Origins.GeneratedGraphExtension
    if (isGraphExtension) {
      if (parentContext == null) {
        reportCompilerBug("No parent bindings found for graph extension ${node.sourceGraph.name}")
      }

      val parentKeysByClass = mutableMapOf<IrClass, IrTypeKey>()
      for ((parentKey, parentNode) in node.allExtendedNodes) {
        val parentNodeClass = parentNode.sourceGraph.metroGraphOrFail

        parentKeysByClass[parentNodeClass] = parentKey

        // Add bindings for the parent itself as a field reference
        // TODO it would be nice if we could do this lazily with addLazyParentKey
        val propertyAccess =
          parentContext.mark(parentKey) ?: reportCompilerBug("Missing parent key $parentKey")
        graph.addBinding(
          parentKey,
          IrBinding.BoundInstance(
            parentKey,
            "parent",
            parentNode.sourceGraph,
            classReceiverParameter = parentNodeClass.thisReceiver,
            providerPropertyAccess = propertyAccess,
          ),
          bindingStack,
        )

        // Add the original type too as an alias
        val regularGraph = parentNode.sourceGraph.sourceGraphIfMetroGraph
        if (regularGraph != parentNode.sourceGraph) {
          val keyType =
            regularGraph.symbol.typeWithArguments(
              parentNode.typeKey.type.requireSimpleType().arguments
            )
          val typeKey = IrTypeKey(keyType)
          superTypeToAlias.putIfAbsent(typeKey, parentKey)
        }
      }

      for (key in parentContext.availableKeys()) {
        // Graph extensions that are scoped instances _in_ their parents may show up here, so we
        // check and continue if we see them
        if (key == node.typeKey) continue
        if (key == node.metroGraph?.generatedGraphExtensionData?.typeKey) continue
        val existingBinding = graph.findBinding(key)
        if (existingBinding != null) {
          // If we already have a binding provisioned in this scenario, ignore the parent's
          // version
          continue
        }

        // Register a lazy parent key that will only call mark() when actually used
        bindingLookup.addLazyParentKey(key) {
          val propertyAccess =
            parentContext.mark(key) ?: reportCompilerBug("Missing parent key $key")

          // Record a lookup for IC when the binding is actually created
          val propertyParentClass = propertyAccess.property.parentAsClass
          trackMemberDeclarationCall(
            node.sourceGraph,
            propertyParentClass.kotlinFqName,
            propertyAccess.property.name.asString(),
          )

          if (key == propertyAccess.parentKey) {
            // Add bindings for the parent itself as a field reference
            IrBinding.BoundInstance(
              key,
              "parent",
              propertyAccess.property,
              classReceiverParameter = propertyAccess.receiverParameter,
              providerPropertyAccess = propertyAccess,
            )
          } else {
            IrBinding.GraphDependency(
              ownerKey = parentKeysByClass.getValue(propertyParentClass),
              graph = node.sourceGraph,
              propertyAccess = propertyAccess,
              typeKey = key,
            )
          }
        }
      }
    }

    // Add MembersInjector bindings defined on injector functions
    for ((contextKey, injector) in node.injectors) {
      val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)

      graph.addInjector(contextKey, entry)
      if (contextKey.typeKey in graph) {
        // Injectors may be requested multiple times, don't double-add a binding
        continue
      }
      bindingStack.withEntry(entry) {
        val param = injector.ir.regularParameters.single()
        val paramType = param.type
        val targetClass = paramType.rawType()
        // Don't return null on missing because it's legal to inject a class with no member
        // injections
        // TODO warn on this?
        val generatedInjectors =
          membersInjectorTransformer.getOrGenerateAllInjectorsFor(targetClass)

        val mergedMappedParameters =
          if (generatedInjectors.isEmpty()) {
              Parameters.empty()
            } else {
              generatedInjectors
                .map { generatedInjector ->
                  // Create a remapper for the target class type parameters
                  val remapper = targetClass.deepRemapperFor(paramType)
                  generatedInjector.mergedParameters(remapper)
                }
                .reduce { current, next -> current.mergeValueParametersWith(next) }
            }
            .withCallableId(injector.callableId)

        val binding =
          IrBinding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters = mergedMappedParameters,
            reportableDeclaration = injector.ir,
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        // Cache in BindingLookup to avoid re-creating it later
        bindingLookup.putBinding(binding)

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

    return graph
  }
}
