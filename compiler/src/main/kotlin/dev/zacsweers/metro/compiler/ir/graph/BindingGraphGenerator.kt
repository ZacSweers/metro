// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsLikeCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrBoundTypeResolver
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroDeclarations
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.ParentContextReader
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.batchTrackForCallingDeclaration
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor

/**
 * Generates an [IrBindingGraph] for the given [node]. This only constructs the graph from available
 * bindings and does _not_ validate it.
 */
internal class BindingGraphGenerator(
  metroContext: IrMetroContext,
  traceScope: TraceScope,
  private val node: GraphNode.Local,
  private val metroDeclarations: MetroDeclarations,
  private val contributionData: IrContributionData,
  private val parentContext: ParentContextReader?,
  private val bindingLookupCache: BindingLookupCache,
  private val boundTypeResolver: IrBoundTypeResolver,
) : IrMetroContext by metroContext, TraceScope by traceScope {

  private val ProviderFactory.isDynamic: Boolean
    get() = this in node.dynamicTypeKeys[typeKey].orEmpty()

  private val BindsLikeCallable.isDynamic: Boolean
    get() = this in node.dynamicTypeKeys[typeKey].orEmpty()

  fun generate(): IrBindingGraph {
    val bindingLookup: BindingLookup
    val graph: IrBindingGraph
    val bindingStack: IrBindingStack
    trace("Construct lookup & graph") {
      bindingLookup =
        trace("Construct BindingLookup") {
          BindingLookup(
            metroContext = metroContext,
            sourceGraph = node.sourceGraph,
            findClassFactory = { clazz ->
              metroDeclarations.findClassFactory(
                clazz,
                previouslyFoundConstructor = null,
                doNotErrorOnMissing = true,
              )
            },
            findMemberInjectors = metroDeclarations::findAllInjectorsFor,
            parentContext = parentContext,
            bindingLookupCache = bindingLookupCache,
          )
        }

      graph =
        trace("Construct IrBindingGraph") {
          IrBindingGraph(
            this@BindingGraphGenerator,
            node,
            newBindingStack = {
              IrBindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.BindingGraphConstruction))
            },
            bindingLookup = bindingLookup,
            contributionData = contributionData,
            boundTypeResolver = boundTypeResolver,
          )
        }

      bindingStack =
        trace("Construct IrBindingStack") {
          IrBindingStack(
            node.sourceGraph,
            metroContext.loggerFor(MetroLogger.Type.BindingGraphConstruction),
          )
        }
    }

    fun putBinding(typeKey: IrTypeKey, isLocallyDeclared: Boolean, binding: IrBinding) {
      bindingLookup.putBinding(binding, isLocallyDeclared = isLocallyDeclared)

      if (options.enableFullBindingGraphValidation) {
        graph.addBinding(typeKey, binding, bindingStack)
      }
    }

    trace("Seed graph instance binding") {
      // Add instance parameters
      val graphInstanceBinding =
        IrBinding.BoundInstance(
          typeKey = node.typeKey,
          nameHint = "${node.sourceGraph.name}Provider",
          reportableDeclaration = node.sourceGraph,
          token = null, // indicates self-binding, code gen uses thisReceiver
        )
      putBinding(graphInstanceBinding.typeKey, isLocallyDeclared = false, graphInstanceBinding)
    }

    // Mapping of supertypes to aliased bindings
    // We populate this for the current graph type first and then
    // add to them when processing extended parent graphs IFF there
    // is not already an existing entry. We do it this way to handle
    // cases where both the child graph and parent graph implement
    // a shared interface. In this scenario, the child alias wins
    // and we do not need to try to add another (duplicate) binding
    val superTypeToAlias = mutableMapOf<IrTypeKey, IrTypeKey>()

    trace("Iterate supertypes") {
      // Add aliases for all its supertypes
      // TODO dedupe supertype iteration
      node.supertypes.forEach { superType ->
        val superTypeKey = IrTypeKey(superType)
        @Suppress("RETURN_VALUE_NOT_USED") superTypeToAlias.putIfAbsent(superTypeKey, node.typeKey)
      }
    }

    trace("Add injectors") {
      // Register MembersInjector functions for deferred binding creation
      // The actual bindings are created in BindingLookup.computeMembersInjectorBindings
      for ((contextKey, injector) in node.injectors) {
        val param = injector.ir.regularParameters.single()
        val paramType = param.type
        // Show the target class being injected, not the MembersInjector<T> type
        val entry =
          IrBindingStack.Entry.injectedAt(
            contextKey = contextKey,
            function = injector.ir,
            displayTypeKey = IrTypeKey(paramType),
          )

        graph.addInjector(contextKey, entry)
        if (contextKey.typeKey in bindingLookup) {
          // Injectors may be requested multiple times, don't double-register
          continue
        }
        // Skip if there's a dynamic replacement for this injector type
        if (contextKey.typeKey in node.dynamicTypeKeys) {
          continue
        }
        // Register the injector function - binding will be created lazily in BindingLookup
        bindingLookup.registerInjectorFunction(contextKey.typeKey, injector.ir, injector.callableId)
      }
    }

    // Collect all inherited data from extended nodes in a single pass
    val inheritedData = trace("Collect inherited data") { collectInheritedData(node) }
    val inheritedProviderFactoryKeys = inheritedData.providerFactoryKeys
    val inheritedProviderFactories = inheritedData.providerFactories
    val inheritedBindsCallableKeys = inheritedData.bindsCallableKeys

    val ownProviderFactoryCount = node.providerFactories.values.sumOf { it.size }
    val inheritedProviderFactoryCount = inheritedProviderFactories.size
    trace(
      "Collect provider factories (own=$ownProviderFactoryCount, inh=$inheritedProviderFactoryCount)"
    ) {
      // Collect all provider factories to add (flatten from lists)
      val providerFactoriesToAdd = buildList {
        node.providerFactories.values.flatten().forEach { factory ->
          add(factory.typeKey to factory)
        }
        addAll(inheritedProviderFactories)
      }

      for ((typeKey, providerFactory) in providerFactoriesToAdd) {
        // Track IC lookups but don't add bindings yet - they'll be added lazily
        trackClassLookup(node.sourceGraph, providerFactory.factoryClass)
        trackFunctionCall(node.sourceGraph, providerFactory.function)
        if (providerFactory is ProviderFactory.Metro) {
          trackFunctionCall(node.sourceGraph, providerFactory.mirrorFunction)
        }

        val isInherited = typeKey in inheritedProviderFactoryKeys
        if (typeKey in bindingLookup && isInherited) {
          // If we already have a binding provisioned in this scenario, ignore the parent's version.
          // This includes multibinding contributors — the same contribution discovered through
          // multiple include/contribution paths should only be registered once.
          continue
        }

        // Skip non-dynamic bindings that have dynamic replacements
        if (!providerFactory.isDynamic && typeKey in node.dynamicTypeKeys) {
          continue
        }

        // typeKey is already the transformed multibinding key
        val targetTypeKey = providerFactory.typeKey
        val isDynamic = providerFactory.isDynamic
        val existingBinding = bindingLookup[targetTypeKey]

        if (isDynamic && existingBinding != null) {
          // Only clear existing if they are not dynamic
          // If existing bindings are also dynamic, keep them both for duplicate detection
          val existingAreDynamic =
            when (existingBinding) {
              is Provided -> existingBinding.providerFactory.isDynamic
              is Alias -> existingBinding.bindsCallable?.isDynamic == true
              else -> false
            }

          if (!existingAreDynamic) {
            // Dynamic binding replaces non-dynamic existing bindings
            bindingLookup.clearBindings(targetTypeKey)
          }
        }

        val contextKey = IrContextualTypeKey(targetTypeKey)

        // Use cached binding if available, otherwise create and cache
        val binding =
          providerFactory.factoryClass.cachedProvidedBinding
            ?: IrBinding.Provided(
                providerFactory = providerFactory,
                contextualTypeKey = contextKey,
                parameters = providerFactory.parameters,
                annotations = providerFactory.annotations,
              )
              .also { providerFactory.factoryClass.cachedProvidedBinding = it }

        // Add the binding to the lookup (duplicates tracked as lists)
        putBinding(binding.typeKey, isLocallyDeclared = !isInherited, binding)
      }
    }

    val ownBindsCount = node.bindsCallables.values.sumOf { it.size }
    val inheritedBindsCount = inheritedData.bindsCallables.size
    trace("Collect binds callables (own=$ownBindsCount, inh=$inheritedBindsCount)") {
      // Collect all binds callables to add (flatten from lists)
      val bindsCallablesToAdd = buildList {
        node.bindsCallables.values.flatten().forEach { callable ->
          add(callable.typeKey to callable)
        }
        // Add inherited from extended nodes (already collected in single pass)
        addAll(inheritedData.bindsCallables)
      }

      // Track IC lookups for all binds callables in one batch: hoists file-path resolution and
      // tracker-lock acquisition out of the per-callable loop.
      trace("Track IC for binds") {
        batchTrackForCallingDeclaration(node.sourceGraph) {
          for ((_, bindsCallable) in bindsCallablesToAdd) {
            trackFunctionCall(bindsCallable.function)
            trackFunctionCall(bindsCallable.callableMetadata.mirrorFunction)
            trackClassLookup(bindsCallable.function.parentAsClass)
            trackClassLookup(bindsCallable.callableMetadata.mirrorFunction.parentAsClass)
          }
        }
      }

      for ((typeKey, bindsCallable) in bindsCallablesToAdd) {

        val isInherited = typeKey in inheritedBindsCallableKeys
        if (typeKey in bindingLookup && isInherited) {
          // If we already have a binding provisioned in this scenario, ignore the parent's version.
          // This includes multibinding contributors, so we ensure the same contribution discovered
          // through multiple include/contribution paths should only be registered once.
          continue
        }

        // Skip non-dynamic bindings that have dynamic replacements
        if (!bindsCallable.isDynamic && typeKey in node.dynamicTypeKeys) {
          continue
        }

        // typeKey is already the transformed multibinding key
        val targetTypeKey = bindsCallable.typeKey
        val isDynamic = bindsCallable.isDynamic
        val existingBinding = bindingLookup[targetTypeKey]

        if (isDynamic && existingBinding != null) {
          // Only clear existing if they are NOT dynamic
          // If existing bindings are also dynamic, keep them for duplicate detection
          val existingAreDynamic =
            when (existingBinding) {
              is Provided -> existingBinding.providerFactory.isDynamic
              is Alias -> existingBinding.bindsCallable?.isDynamic == true
              else -> false
            }
          if (!existingAreDynamic) {
            // Dynamic binding replaces non-dynamic existing bindings
            bindingLookup.clearBindings(targetTypeKey)
          }
        }

        val mirrorFunction = bindsCallable.callableMetadata.mirrorFunction

        // Use cached binding if available, otherwise create and cache
        val binding =
          mirrorFunction.cachedAliasBinding
            ?: trace("Resolve binds alias binding") {
              val parameters = bindsCallable.function.parameters()
              val bindsImplType =
                parameters.extensionOrFirstParameter?.contextualTypeKey
                  ?: reportCompilerBug(
                    "Missing receiver parameter for @Binds function: ${bindsCallable.function}"
                  )
              IrBinding.Alias(
                  typeKey = targetTypeKey,
                  aliasedType = bindsImplType.typeKey,
                  bindsCallable = bindsCallable,
                  parameters = parameters,
                )
                .also { mirrorFunction.cachedAliasBinding = it }
            }

        // Add the binding to the lookup (duplicates tracked as lists)
        putBinding(binding.typeKey, isLocallyDeclared = !isInherited, binding)
      }
    }

    // For graph extensions, use the original factory creator to reference source parameter
    // declarations
    val originalCreator = node.originalCreator ?: node.creator

    trace("Process creator params") {
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
            val declaration =
              originalCreator?.parametersByTypeKey?.get(paramTypeKey)?.ir ?: creatorParam.ir!!

            // Only add the bound instance if there's no dynamic replacement
            val binding =
              IrBinding.BoundInstance(
                parameter = creatorParam,
                reportableLocation = declaration,
                isGraphInput = true,
              )

            putBinding(binding.typeKey, isLocallyDeclared = true, binding)
            // Track as locally declared for unused key reporting
            bindingLookup.trackDeclaredKey(paramTypeKey)

            val rawType = creatorParam.type.rawType()
            // Add the original type too as an alias
            val regularGraph = rawType.sourceGraphIfMetroGraph
            if (regularGraph != rawType) {
              val keyType =
                regularGraph.symbol.typeWithArguments(
                  creatorParam.type.requireSimpleType(creatorParam.ir).arguments
                )
              val typeKey = IrTypeKey(keyType)
              @Suppress("RETURN_VALUE_NOT_USED") superTypeToAlias.putIfAbsent(typeKey, paramTypeKey)
            }
          }
        }
      }
    }

    trace("Process binding containers") {
      val allManagedBindingContainerInstances = buildSet {
        addAll(node.bindingContainers)
        addAll(inheritedData.bindingContainers)
      }

      for (bindingContainer in allManagedBindingContainerInstances) {
        val typeKey = IrTypeKey(bindingContainer)

        val hasDynamicReplacement = typeKey in node.dynamicTypeKeys

        if (!hasDynamicReplacement) {
          val declaration =
            originalCreator?.parametersByTypeKey?.get(typeKey)?.ir ?: bindingContainer

          val irElement = node.annotationDeclaredBindingContainers[typeKey]
          val isGraphInput = irElement != null

          // Only add the bound instance if there's no dynamic replacement
          val binding =
            IrBinding.BoundInstance(
              typeKey = typeKey,
              nameHint = bindingContainer.name.asString(),
              irElement = irElement,
              reportableDeclaration = declaration,
              isGraphInput = isGraphInput,
            )
          putBinding(binding.typeKey, isLocallyDeclared = isGraphInput, binding)
          // Track as locally declared for unused key reporting (only if it's a graph input)
          if (isGraphInput) {
            bindingLookup.trackDeclaredKey(typeKey)
          }
        }
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

    trace("Process multibindings") {
      val allMultibindsCallables = buildList {
        addAll(node.multibindsCallables)
        addAll(inheritedData.multibindsCallables)
      }

      allMultibindsCallables.forEach { multibindsCallable ->
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
    }

    trace("Process optional bindings") {
      val allOptionalKeys = buildMap {
        putAll(node.optionalKeys)
        putAll(inheritedData.optionalKeys)
      }

      // Register optional bindings for lazy creation (only when accessed)
      for ((optionalKey, callables) in allOptionalKeys) {
        for (callable in callables) {
          bindingLookup.registerOptionalBinding(optionalKey, callable)
        }
      }
    }

    trace("Create supertype aliases") {
      // Traverse all parent graph supertypes to create binding aliases as needed.
      // If it's a contributed graph, add an alias for the parent types since that's what
      // bindings will look for. i.e. LoggedInGraphImpl -> LoggedInGraph + supertypes
      // (Already collected in single pass via collectInheritedData)
      for ((parentTypeKey, aliasedTypeKey) in inheritedData.supertypeAliases) {
        @Suppress("RETURN_VALUE_NOT_USED")
        superTypeToAlias.putIfAbsent(parentTypeKey, aliasedTypeKey)
      }

      // Now that we've processed all supertypes/aliases
      for ((superTypeKey, aliasedType) in superTypeToAlias) {
        // We may have already added a `@Binds` declaration explicitly, this is ok!
        // We don't double-add if it's already in the lookup, which can be the case for graph nodes
        // TODO warning?
        if (superTypeKey !in bindingLookup && superTypeKey !in node.dynamicTypeKeys) {
          val binding = IrBinding.Alias(superTypeKey, aliasedType, null, Parameters.empty())
          putBinding(binding.typeKey, isLocallyDeclared = false, binding)
        }
      }
    }

    trace("Process accessors") {
      val accessorsToAdd = buildList {
        addAll(node.accessors)
        // Pass down @Multibinds declarations in the same way we do for multibinding providers
        // (Already collected in single pass via collectInheritedData)
        addAll(inheritedData.multibindingAccessors)
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
    }

    trace("Process extensions") {
      for ((key, accessors) in node.graphExtensions) {
        accessors.forEach { accessor ->
          val shouldAddBinding =
            accessor.isFactory &&
              // It's allowed to specify multiple accessors for the same factory
              accessor.key.typeKey !in bindingLookup &&
              // Don't add a binding if the graph itself implements the factory
              accessor.key.typeKey.classId !in node.supertypeClassIds &&
              // Don't add a binding if there's a dynamic replacement
              accessor.key.typeKey !in node.dynamicTypeKeys

          if (shouldAddBinding) {
            val binding =
              IrBinding.GraphExtensionFactory(
                typeKey = accessor.key.typeKey,
                extensionTypeKey = key,
                parent = node.metroGraph!!,
                parentKey = IrTypeKey(node.metroGraph!!),
                accessor = accessor.accessor.ir,
              )
            putBinding(binding.typeKey, false, binding)
          }
        }
      }
    }

    trace("Process included graph nodes") {
      // Add bindings from graph dependencies
      // TODO dedupe this allDependencies iteration with graph gen
      // TODO try to make accessors in this single-pass
      // Only add it if it's a directly included node. Indirect will be propagated by metro
      // accessors
      for ((depNodeKey, depNode) in node.includedGraphNodes) {
        // Only add accessors for included types
        depNode.accessors.forEach { (contextualTypeKey, getter, _) ->
          // Add a ref to the included graph if not already present
          if (depNodeKey !in bindingLookup) {
            val declaration =
              originalCreator?.parametersByTypeKey?.get(depNodeKey)?.ir ?: depNode.sourceGraph

            val binding =
              IrBinding.BoundInstance(
                depNodeKey,
                "${depNode.sourceGraph.name}Provider",
                declaration,
                isGraphInput = true,
              )
            putBinding(binding.typeKey, isLocallyDeclared = true, binding)
            // Track as locally declared for unused key reporting
            bindingLookup.trackDeclaredKey(depNodeKey)
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

          val binding =
            IrBinding.GraphDependency(
              ownerKey = depNodeKey,
              graph = depNode.sourceGraph,
              getter = getterToUse,
              typeKey = contextualTypeKey.typeKey,
              contextualTypeKey = contextualTypeKey,
            )
          putBinding(binding.typeKey, isLocallyDeclared = true, binding)
          // Record a lookup for IC
          trackFunctionCall(node.sourceGraph, irGetter)
          trackFunctionCall(node.sourceGraph, getterToUse)
        }
      }
    }

    // Add scoped accessors from directly known parent bindings
    // Only present if this is a contributed graph
    val isGraphExtension = node.sourceGraph.origin == Origins.GeneratedGraphExtension
    if (isGraphExtension) {
      trace("Process inherited bindings") {
        if (parentContext == null) {
          reportCompilerBug("No parent bindings found for graph extension ${node.sourceGraph.name}")
        }

        val parentKeysByClass = mutableMapOf<IrClass, IrTypeKey>()
        for ((parentKey, parentNode) in node.allParentGraphs) {
          trace("Process parent ${parentNode.metroGraphOrFail.name}") {
            val parentNodeClass = parentNode.sourceGraph.metroGraphOrFail

            parentKeysByClass[parentNodeClass] = parentKey

            // Add bindings for the parent itself as a field reference
            // TODO it would be nice if we could do this lazily with addLazyParentKey
            val token =
              parentContext.mark(parentKey) ?: reportCompilerBug("Missing parent key $parentKey")
            val binding =
              IrBinding.BoundInstance(
                typeKey = parentKey,
                nameHint = "parent",
                reportableDeclaration = parentNode.sourceGraph,
                token = token,
              )
            putBinding(binding.typeKey, isLocallyDeclared = false, binding)

            // Add the original type too as an alias
            val regularGraph = parentNode.sourceGraph.sourceGraphIfMetroGraph
            if (regularGraph != parentNode.sourceGraph) {
              val keyType =
                regularGraph.symbol.typeWithArguments(
                  parentNode.typeKey.type.requireSimpleType().arguments
                )
              val typeKey = IrTypeKey(keyType)
              @Suppress("RETURN_VALUE_NOT_USED") superTypeToAlias.putIfAbsent(typeKey, parentKey)
            }
          }
        }

        trace("Add bindings from parent context") {
          for (key in parentContext.availableKeys()) {
            // Graph extensions that are scoped instances _in_ their parents may show up here, so we
            // check and continue if we see them
            if (key == node.typeKey) continue
            if (key == node.metroGraph?.generatedGraphExtensionData?.typeKey) continue
            // Use bindingLookup as the source of truth. graph.findBinding() only reflects keys
            // added through graph.addBinding(), which is disabled when full graph validation is
            // off.
            if (key in bindingLookup) {
              // If we already have a binding provisioned in this scenario, ignore the parent's
              // version
              continue
            }

            // If this key is a multibinding contribution (has @MultibindingElement qualifier),
            // register it so the child's multibinding will include this parent contribution
            if (key.multibindingKeyData != null) {
              bindingLookup.registerMultibindingContributionByBindingId(key)
            }

            // Register a lazy parent key that will only call mark() when actually used
            bindingLookup.addLazyParentKey(key) {
              val token = parentContext.mark(key) ?: reportCompilerBug("Missing parent key $key")

              // IC tracking will be done during generation when the actual property is resolved

              if (key == token.ownerGraphKey) {
                // Add bindings for the parent itself as a field reference
                IrBinding.BoundInstance(
                  typeKey = key,
                  nameHint = "parent",
                  reportableDeclaration = null, // will be available during generation
                  token = token,
                )
              } else {
                IrBinding.GraphDependency(
                  ownerKey = token.ownerGraphKey,
                  graph = node.sourceGraph,
                  token = token,
                  typeKey = key,
                )
              }
            }
          }
        }
      }
    }

    return graph
  }

  /** Collects all inherited data from parent nodes in a single pass. */
  private fun collectInheritedData(node: GraphNode.Local): InheritedGraphData {
    val parentSourceGraph = node.parentGraph?.sourceGraph
    val raw =
      if (parentSourceGraph != null) {
        bindingLookupCache.getOrPutRawInheritedGraphData(parentSourceGraph) {
          computeRawInheritedGraphData(node)
        }
      } else {
        // No parent: compute (will be empty fast-path) without caching.
        computeRawInheritedGraphData(node)
      }
    return raw.applyDirectlyProvidedFilter(node.directlyProvidedKeys)
  }

  /**
   * Aggregates parent-chain data with all parent-only filters applied (graph-private exclusions,
   * scoped exclusions, intra-chain dedup). The per-child `directlyProvidedKeys` filter is applied
   * later in [RawInheritedGraphData.applyDirectlyProvidedFilter] so siblings can reuse this.
   */
  private fun computeRawInheritedGraphData(node: GraphNode.Local): RawInheritedGraphData {
    val providerFactories = mutableListOf<RawProviderFactoryEntry>()
    val providerFactoryKeys = mutableSetOf<IrTypeKey>()
    val bindsCallableKeys = mutableSetOf<IrTypeKey>()
    val bindsCallables = mutableListOf<RawBindsCallableEntry>()
    val bindingContainers = mutableSetOf<IrClass>()
    val multibindsCallables = mutableSetOf<MultibindsCallable>()
    val optionalKeys = mutableMapOf<IrTypeKey, MutableSet<BindsOptionalOfCallable>>()
    val supertypeAliases = mutableMapOf<IrTypeKey, IrTypeKey>()
    val multibindingAccessors = mutableListOf<GraphAccessor>()

    for ((typeKey, extendedNode) in node.allParentGraphs) {
      val isDynamicParent =
        extendedNode is GraphNode.Local && extendedNode.dynamicTypeKeys.isNotEmpty()

      val alreadyCollectedKeys = providerFactoryKeys + bindsCallableKeys

      // Collect provider factories (non-scoped, not already collected from a closer parent).
      // Skip @GraphPrivate factories — private contributions should not leak to child graphs.
      // Dynamic parent bindings take precedence over keys collected up-chain so they can override.
      for ((key, factories) in extendedNode.providerFactories) {
        if (
          key in alreadyCollectedKeys && !(isDynamicParent && key in extendedNode.dynamicTypeKeys)
        ) {
          continue
        }
        for (factory in factories) {
          if (!factory.annotations.isScoped && key !in extendedNode.graphPrivateKeys) {
            val isDynamicInParent = isDynamicParent && key in extendedNode.dynamicTypeKeys
            providerFactories.add(RawProviderFactoryEntry(key, factory, isDynamicInParent))
            providerFactoryKeys.add(key)
          }
        }
      }

      // Collect binds callables.
      // Skip binds whose source type is graph-private in the parent — the child can't resolve
      // the private source. The binds result type is promoted to the parent context instead, so
      // the child resolves it as a GraphDependency.
      // Dynamic parent bindings take precedence over keys collected up-chain.
      for ((key, callables) in extendedNode.bindsCallables) {
        if (
          key in alreadyCollectedKeys && !(isDynamicParent && key in extendedNode.dynamicTypeKeys)
        ) {
          continue
        }
        for (callable in callables) {
          if (callable.source in extendedNode.graphPrivateKeys) continue
          val isDynamicInParent = isDynamicParent && key in extendedNode.dynamicTypeKeys
          bindsCallableKeys.add(key)
          bindsCallables.add(RawBindsCallableEntry(key, callable, isDynamicInParent))
        }
      }

      // Collect binding containers (only from Local nodes).
      if (extendedNode is GraphNode.Local) {
        bindingContainers.addAll(extendedNode.bindingContainers)
      }

      // Collect multibinds callables.
      multibindsCallables.addAll(extendedNode.multibindsCallables)

      // Collect optional keys.
      for ((optKey, callables) in extendedNode.optionalKeys) {
        optionalKeys.getOrPut(optKey) { mutableSetOf() }.addAll(callables)
      }

      // Collect supertype aliases for parent graphs.
      for (superType in extendedNode.supertypes) {
        val parentTypeKey = IrTypeKey(superType)
        if (parentTypeKey != typeKey) {
          @Suppress("RETURN_VALUE_NOT_USED") supertypeAliases.putIfAbsent(parentTypeKey, typeKey)
        }
      }

      // Collect multibinding accessors.
      for (accessor in extendedNode.accessors) {
        if (accessor.metroFunction.annotations.isMultibinds) {
          multibindingAccessors.add(accessor)
        }
      }
    }

    return RawInheritedGraphData(
      providerFactories = providerFactories,
      bindsCallables = bindsCallables,
      bindingContainers = bindingContainers,
      multibindsCallables = multibindsCallables,
      optionalKeys = optionalKeys,
      supertypeAliases = supertypeAliases,
      multibindingAccessors = multibindingAccessors,
    )
  }
}

/**
 * Pre-aggregated parent-chain data with all parent-only filters applied. Siblings sharing the same
 * direct parent get the same value via [BindingLookupCache.getOrPutRawInheritedGraphData].
 */
private class RawInheritedGraphData(
  val providerFactories: List<RawProviderFactoryEntry>,
  val bindsCallables: List<RawBindsCallableEntry>,
  val bindingContainers: Set<IrClass>,
  val multibindsCallables: Set<MultibindsCallable>,
  val optionalKeys: Map<IrTypeKey, Set<BindsOptionalOfCallable>>,
  val supertypeAliases: Map<IrTypeKey, IrTypeKey>,
  val multibindingAccessors: List<GraphAccessor>,
) {
  fun applyDirectlyProvidedFilter(directlyProvidedKeys: Set<IrTypeKey>): InheritedGraphData {
    val outProviderFactories = mutableSetOf<Pair<IrTypeKey, ProviderFactory>>()
    val outProviderFactoryKeys = mutableSetOf<IrTypeKey>()
    val outBindsCallableKeys = mutableSetOf<IrTypeKey>()
    val outBindsCallables = mutableListOf<Pair<IrTypeKey, BindsCallable>>()
    for ((key, factory, isDynamicInParent) in providerFactories) {
      if (isDynamicInParent || key !in directlyProvidedKeys) {
        outProviderFactories.add(key to factory)
        outProviderFactoryKeys.add(key)
      }
    }
    for ((key, callable, isDynamicInParent) in bindsCallables) {
      if (isDynamicInParent || key !in directlyProvidedKeys) {
        outBindsCallableKeys.add(key)
        outBindsCallables.add(key to callable)
      }
    }
    return InheritedGraphData(
      providerFactories = outProviderFactories,
      providerFactoryKeys = outProviderFactoryKeys,
      bindsCallableKeys = outBindsCallableKeys,
      bindsCallables = outBindsCallables,
      bindingContainers = bindingContainers,
      multibindsCallables = multibindsCallables,
      optionalKeys = optionalKeys,
      supertypeAliases = supertypeAliases,
      multibindingAccessors = multibindingAccessors,
    )
  }
}

private data class RawProviderFactoryEntry(
  val key: IrTypeKey,
  val factory: ProviderFactory,
  val isDynamicInParent: Boolean,
)

private data class RawBindsCallableEntry(
  val key: IrTypeKey,
  val callable: BindsCallable,
  val isDynamicInParent: Boolean,
)

/**
 * Data collected from parent nodes in a single pass. Avoids multiple iterations over
 * allParentGraphs.
 */
private data class InheritedGraphData(
  val providerFactories: Set<Pair<IrTypeKey, ProviderFactory>>,
  val providerFactoryKeys: Set<IrTypeKey>,
  val bindsCallableKeys: Set<IrTypeKey>,
  val bindsCallables: List<Pair<IrTypeKey, BindsCallable>>,
  val bindingContainers: Set<IrClass>,
  val multibindsCallables: Set<MultibindsCallable>,
  val optionalKeys: Map<IrTypeKey, Set<BindsOptionalOfCallable>>,
  val supertypeAliases: Map<IrTypeKey, IrTypeKey>,
  val multibindingAccessors: List<GraphAccessor>,
)

/** Cached [IrBinding.Alias] binding for this binds callable's mirror function. */
internal var IrSimpleFunction.cachedAliasBinding: IrBinding.Alias? by
  irAttribute(copyByDefault = false)

/** Cached [IrBinding.Provided] binding for this provider factory class. */
internal var IrClass.cachedProvidedBinding: IrBinding.Provided? by
  irAttribute(copyByDefault = false)
