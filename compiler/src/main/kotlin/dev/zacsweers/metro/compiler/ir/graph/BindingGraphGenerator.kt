// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsLikeCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.remapTypes
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformers.InjectConstructorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor

/**
 * Generates an [IrBindingGraph] for the given [node]. This only constructs the graph from available
 * bindings and does _not_ validate it.
 */
internal class BindingGraphGenerator(
  metroContext: IrMetroContext,
  private val node: GraphNode.Local,
  // TODO preprocess these instead and just lookup via irAttribute
  private val injectConstructorTransformer: InjectConstructorTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val contributionData: IrContributionData,
  private val parentContext: ParentContext?,
) : IrMetroContext by metroContext {

  private val ProviderFactory.isDynamic: Boolean
    get() = this in node.dynamicTypeKeys[typeKey].orEmpty()

  private val BindsLikeCallable.isDynamic: Boolean
    get() = this in node.dynamicTypeKeys[typeKey].orEmpty()

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
      IrBinding.BoundInstance(
        typeKey = node.typeKey,
        nameHint = "${node.sourceGraph.name}Provider",
        reportableDeclaration = node.sourceGraph,
        token = null, // indicates self-binding, code gen uses thisReceiver
      )
    bindingLookup.putBinding(graphInstanceBinding, isLocallyDeclared = false)

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
    node.supertypes.forEach { superType ->
      val superTypeKey = IrTypeKey(superType)
      superTypeToAlias.putIfAbsent(superTypeKey, node.typeKey)
    }

    // Collect all inherited data from extended nodes in a single pass
    val inheritedData = collectInheritedData(node)
    val inheritedProviderFactoryKeys = inheritedData.providerFactoryKeys
    val inheritedProviderFactories = inheritedData.providerFactories
    val inheritedBindsCallableKeys = inheritedData.bindsCallableKeys

    // Collect all provider factories to add (flatten from lists)
    val providerFactoriesToAdd = buildList {
      node.providerFactories.values.flatten().forEach { factory -> add(factory.typeKey to factory) }
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
      if (!providerFactory.annotations.isIntoMultibinding && typeKey in graph && isInherited) {
        // If we already have a binding provisioned in this scenario, ignore the parent's version
        continue
      }

      // Skip non-dynamic bindings that have dynamic replacements
      if (!providerFactory.isDynamic && typeKey in node.dynamicTypeKeys) {
        continue
      }

      // typeKey is already the transformed multibinding key
      val targetTypeKey = providerFactory.typeKey
      val isDynamic = providerFactory.isDynamic
      val existingBindings = bindingLookup.getBindings(targetTypeKey)

      if (isDynamic && existingBindings != null) {
        // Only clear existing if they are not dynamic
        // If existing bindings are also dynamic, keep them both for duplicate detection
        val existingAreDynamic =
          existingBindings.getOrNull(0)?.let { binding ->
            when (binding) {
              is IrBinding.Provided -> binding.providerFactory.isDynamic
              is IrBinding.Alias -> binding.bindsCallable?.isDynamic == true
              else -> false
            }
          } ?: false

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
      bindingLookup.putBinding(binding, isLocallyDeclared = !isInherited)

      if (options.enableFullBindingGraphValidation) {
        graph.addBinding(binding.typeKey, binding, bindingStack)
      } else {
        // The actual binding will be added lazily via BindingLookup when needed
      }
    }

    // Collect all binds callables to add (flatten from lists)
    val bindsCallablesToAdd = buildList {
      node.bindsCallables.values.flatten().forEach { callable -> add(callable.typeKey to callable) }
      // Add inherited from extended nodes (already collected in single pass)
      addAll(inheritedData.bindsCallables)
    }

    for ((typeKey, bindsCallable) in bindsCallablesToAdd) {
      // Track IC lookups but don't add bindings yet - they'll be added lazily
      trackFunctionCall(node.sourceGraph, bindsCallable.function)
      trackFunctionCall(node.sourceGraph, bindsCallable.callableMetadata.mirrorFunction)
      trackClassLookup(node.sourceGraph, bindsCallable.function.parentAsClass)
      trackClassLookup(
        node.sourceGraph,
        bindsCallable.callableMetadata.mirrorFunction.parentAsClass,
      )

      val isInherited = typeKey in inheritedBindsCallableKeys
      if (
        !bindsCallable.callableMetadata.annotations.isIntoMultibinding &&
          typeKey in graph &&
          isInherited
      ) {
        // If we already have a binding provisioned in this scenario, ignore the parent's version
        continue
      }

      // Skip non-dynamic bindings that have dynamic replacements
      if (!bindsCallable.isDynamic && typeKey in node.dynamicTypeKeys) {
        continue
      }

      // typeKey is already the transformed multibinding key
      val targetTypeKey = bindsCallable.typeKey
      val isDynamic = bindsCallable.isDynamic
      val existingBindings = bindingLookup.getBindings(targetTypeKey)

      if (isDynamic && existingBindings != null) {
        // Only clear existing if they are NOT dynamic
        // If existing bindings are also dynamic, keep them for duplicate detection
        val existingAreDynamic =
          existingBindings.firstOrNull()?.let { binding ->
            when (binding) {
              is IrBinding.Provided -> binding.providerFactory.isDynamic
              is IrBinding.Alias -> binding.bindsCallable?.isDynamic == true
              else -> false
            }
          } ?: false
        if (!existingAreDynamic) {
          // Dynamic binding replaces non-dynamic existing bindings
          bindingLookup.clearBindings(targetTypeKey)
        }
      }

      val mirrorFunction = bindsCallable.callableMetadata.mirrorFunction

      // Use cached binding if available, otherwise create and cache
      val binding =
        mirrorFunction.cachedAliasBinding
          ?: run {
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
      bindingLookup.putBinding(binding, isLocallyDeclared = !isInherited)

      if (options.enableFullBindingGraphValidation) {
        graph.addBinding(binding.typeKey, binding, bindingStack)
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
          bindingLookup.putBinding(
            IrBinding.BoundInstance(creatorParam, creatorParam.ir!!, isGraphInput = true),
            isLocallyDeclared = true,
          )
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
            superTypeToAlias.putIfAbsent(typeKey, paramTypeKey)
          }
        }
      }
    }

    val allManagedBindingContainerInstances = buildSet {
      addAll(node.bindingContainers)
      addAll(inheritedData.bindingContainers)
    }
    for (it in allManagedBindingContainerInstances) {
      val typeKey = IrTypeKey(it)

      val hasDynamicReplacement = typeKey in node.dynamicTypeKeys

      if (!hasDynamicReplacement) {
        val declaration = node.creator?.parametersByTypeKey?.get(typeKey)?.ir ?: it

        val irElement = node.annotationDeclaredBindingContainers[typeKey]
        val isGraphInput = irElement != null

        // Only add the bound instance if there's no dynamic replacement
        bindingLookup.putBinding(
          IrBinding.BoundInstance(
            typeKey = typeKey,
            nameHint = it.name.asString(),
            irElement = irElement,
            reportableDeclaration = declaration,
            isGraphInput = isGraphInput,
          ),
          isLocallyDeclared = isGraphInput,
        )
        // Track as locally declared for unused key reporting (only if it's a graph input)
        if (isGraphInput) {
          bindingLookup.trackDeclaredKey(typeKey)
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

    // Traverse all parent graph supertypes to create binding aliases as needed.
    // If it's a contributed graph, add an alias for the parent types since that's what
    // bindings will look for. i.e. LoggedInGraphImpl -> LoggedInGraph + supertypes
    // (Already collected in single pass via collectInheritedData)
    for ((parentTypeKey, aliasedTypeKey) in inheritedData.supertypeAliases) {
      superTypeToAlias.putIfAbsent(parentTypeKey, aliasedTypeKey)
    }

    // Now that we've processed all supertypes/aliases
    for ((superTypeKey, aliasedType) in superTypeToAlias) {
      // We may have already added a `@Binds` declaration explicitly, this is ok!
      // We don't double-add if it's already in the lookup, which can be the case for graph nodes
      // TODO warning?
      if (superTypeKey !in bindingLookup && superTypeKey !in node.dynamicTypeKeys) {
        bindingLookup.putBinding(
          IrBinding.Alias(superTypeKey, aliasedType, null, Parameters.empty()),
          isLocallyDeclared = false,
        )
      }
    }

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
          bindingLookup.putBinding(
            IrBinding.GraphExtensionFactory(
              typeKey = accessor.key.typeKey,
              extensionTypeKey = key,
              parent = node.metroGraph!!,
              parentKey = IrTypeKey(node.metroGraph!!),
              accessor = accessor.accessor.ir,
            ),
            false,
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
      depNode.accessors.forEach { (contextualTypeKey, getter, _) ->
        // Add a ref to the included graph if not already present
        if (depNodeKey !in bindingLookup) {
          val declaration =
            node.creator?.parametersByTypeKey?.get(depNodeKey)?.ir ?: depNode.sourceGraph

          bindingLookup.putBinding(
            IrBinding.BoundInstance(
              depNodeKey,
              "${depNode.sourceGraph.name}Provider",
              declaration,
              isGraphInput = true,
            ),
            isLocallyDeclared = true,
          )
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

        bindingLookup.putBinding(
          IrBinding.GraphDependency(
            ownerKey = depNodeKey,
            graph = depNode.sourceGraph,
            getter = getterToUse,
            typeKey = contextualTypeKey.typeKey,
          ),
          isLocallyDeclared = true,
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
      for ((parentKey, parentNode) in node.allParentGraphs) {
        val parentNodeClass = parentNode.sourceGraph.metroGraphOrFail

        parentKeysByClass[parentNodeClass] = parentKey

        // Add bindings for the parent itself as a field reference
        // TODO it would be nice if we could do this lazily with addLazyParentKey
        val token =
          parentContext.mark(parentKey) ?: reportCompilerBug("Missing parent key $parentKey")
        bindingLookup.putBinding(
          IrBinding.BoundInstance(
            typeKey = parentKey,
            nameHint = "parent",
            reportableDeclaration = parentNode.sourceGraph,
            token = token,
          ),
          isLocallyDeclared = false,
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
        // Skip if there's a dynamic replacement for this key
        if (key in node.dynamicTypeKeys) continue
        val existingBinding = graph.findBinding(key)
        if (existingBinding != null) {
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

    // Add MembersInjector bindings defined on injector functions
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
      if (contextKey.typeKey in graph) {
        // Injectors may be requested multiple times, don't double-add a binding
        continue
      }
      // Skip if there's a dynamic replacement for this injector type
      if (contextKey.typeKey in node.dynamicTypeKeys) {
        continue
      }
      bindingStack.withEntry(entry) {
        val targetClass = paramType.rawType()
        // Don't return null on missing because it's legal to inject a class with no member
        // injections
        // TODO warn on this?
        val generatedInjectors =
          membersInjectorTransformer.getOrGenerateAllInjectorsFor(targetClass)

        val remapper = targetClass.deepRemapperFor(paramType)

        val mergedMappedParameters =
          if (generatedInjectors.isEmpty()) {
              Parameters.empty()
            } else {
              generatedInjectors
                .map { generatedInjector -> generatedInjector.mergedParameters(remapper) }
                .reduce { current, next -> current.mergeValueParametersWith(next) }
            }
            .withCallableId(injector.callableId)

        // Supertype injector keys: all injectors except the last one (which is the target class)
        // that have @HasMemberInjections. The list is in base-to-derived order, so dropLast(1)
        // gives us the supertypes.
        val supertypeInjectorKeys =
          if (generatedInjectors.size > 1) {
            generatedInjectors
              .dropLast(1)
              .filter { it.sourceClass.hasAnnotation(Symbols.ClassIds.HasMemberInjections) }
              .map { injectorClass ->
                IrContextualTypeKey(injectorClass.typeKey.remapTypes(remapper))
              }
          } else {
            emptyList()
          }

        val binding =
          IrBinding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters = mergedMappedParameters,
            reportableDeclaration = injector.ir,
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
            supertypeMembersInjectorKeys = supertypeInjectorKeys,
          )

        // Cache in BindingLookup to avoid re-creating it later
        bindingLookup.putBinding(binding, isLocallyDeclared = true)

        graph.addBinding(contextKey.typeKey, binding, bindingStack)

        // Ensure that we traverse the target class's superclasses and lookup relevant bindings for
        // them too, namely ancestor member injectors
        val extraBindings =
          bindingLookup.lookup(
            IrContextualTypeKey.from(param),
            graph.bindingsSnapshot(),
            bindingStack,
          ) { _, _ ->
            // Duplicates will be reported later during graph seal
          }
        for (extraBinding in extraBindings) {
          graph.addBinding(extraBinding.typeKey, extraBinding, bindingStack)
        }
      }
    }

    return graph
  }

  /** Collects all inherited data from parent nodes in a single pass. */
  private fun collectInheritedData(node: GraphNode.Local): InheritedGraphData {
    val providerFactories = mutableSetOf<Pair<IrTypeKey, ProviderFactory>>()
    val providerFactoryKeys = mutableSetOf<IrTypeKey>()
    val bindsCallableKeys = mutableSetOf<IrTypeKey>()
    val bindsCallables = mutableListOf<Pair<IrTypeKey, BindsCallable>>()
    val bindingContainers = mutableSetOf<IrClass>()
    val multibindsCallables = mutableSetOf<MultibindsCallable>()
    val optionalKeys = mutableMapOf<IrTypeKey, MutableSet<BindsOptionalOfCallable>>()
    val supertypeAliases = mutableMapOf<IrTypeKey, IrTypeKey>()
    val multibindingAccessors = mutableListOf<GraphAccessor>()

    for ((typeKey, extendedNode) in node.allParentGraphs) {
      // Collect provider factories (non-scoped, not already in current node)
      for ((key, factories) in extendedNode.providerFactories) {
        if (key !in node.providerFactories) {
          for (factory in factories) {
            if (!factory.annotations.isScoped) {
              providerFactories.add(key to factory)
              providerFactoryKeys.add(key)
            }
          }
        }
      }

      // Collect binds callables (not already in current node)
      for ((key, callables) in extendedNode.bindsCallables) {
        if (key !in node.bindsCallables) {
          bindsCallableKeys.add(key)
          for (callable in callables) {
            bindsCallables.add(key to callable)
          }
        }
      }

      // Collect binding containers (only from Local nodes)
      if (extendedNode is GraphNode.Local) {
        bindingContainers.addAll(extendedNode.bindingContainers)
      }

      // Collect multibinds callables
      multibindsCallables.addAll(extendedNode.multibindsCallables)

      // Collect optional keys
      for ((optKey, callables) in extendedNode.optionalKeys) {
        optionalKeys.getOrPut(optKey) { mutableSetOf() }.addAll(callables)
      }

      // Collect supertype aliases for parent graphs
      for (superType in extendedNode.supertypes) {
        val parentTypeKey = IrTypeKey(superType)
        if (parentTypeKey != typeKey) {
          supertypeAliases.putIfAbsent(parentTypeKey, typeKey)
        }
      }

      // Collect multibinding accessors
      for (accessor in extendedNode.accessors) {
        if (accessor.metroFunction.annotations.isMultibinds) {
          multibindingAccessors.add(accessor)
        }
      }
    }

    return InheritedGraphData(
      providerFactories = providerFactories,
      providerFactoryKeys = providerFactoryKeys,
      bindsCallableKeys = bindsCallableKeys,
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
