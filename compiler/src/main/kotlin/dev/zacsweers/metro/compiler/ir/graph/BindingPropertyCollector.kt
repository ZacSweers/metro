// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.canonicalize
import dev.zacsweers.metro.compiler.ir.wrapInProvider

private const val INITIAL_VALUE = 512

/**
 * Computes the set of bindings that must end up in properties.
 *
 * Uses reverse topological order to correctly handle second-order effects: if a binding is in a
 * factory path, its dependencies are accessed as providers and should be counted.
 *
 * A binding is in a "factory path" when its factory is created (either cached in a property or
 * inline). This happens when:
 * 1. The binding has a property (scoped, assisted, or factoryRefCount > 1)
 * 2. The binding is accessed via Provider/Lazy (factoryRefCount > 0)
 *
 * Multibindings are tracked by their [IrContextualTypeKey] (stripped of outer scalar wrappers) to
 * distinguish variants like `Map<K, V>` vs `Map<K, Provider<V>>`, which require different code
 * generation.
 */
internal class BindingPropertyCollector(
  private val metroContext: IrMetroContext,
  private val graph: IrBindingGraph,
  private val sortedKeys: List<IrTypeKey>,
  private val roots: List<IrContextualTypeKey> = emptyList(),
  /** Injector function roots specifically, these don't create MembersInjector instances. */
  private val injectorRoots: Set<IrContextualTypeKey> = emptySet(),
  private val extraKeeps: Collection<IrContextualTypeKey> = emptyList(),
  /** Requests served by cached expressions and the dependency each initializer reads. */
  private val cachedRequests: Map<IrContextualTypeKey, IrContextualTypeKey> = emptyMap(),
  private val deferredTypes: Set<IrTypeKey> = emptySet(),
  /** Keys that are reachable from roots, used to filter the init order. */
  private val reachableKeys: Set<IrTypeKey> = emptySet(),
  /** Returns true for bindings that reuse an unchanged parent property instead of a local one. */
  private val reuseBinding: (IrContextualTypeKey) -> Boolean = { false },
  /** Whether accessors for the same key can call each other. Runtime tracing turns this off. */
  private val accessorsCanShareCode: Boolean = true,
) {

  data class CollectedProperty(
    val binding: IrBinding,
    val propertyKind: PropertyKind,
    /** The contextual type key this property was collected for (relevant for multibindings). */
    val contextualTypeKey: IrContextualTypeKey,
    /**
     * Whether this property returns a Provider type. Always true for FIELD properties, and true for
     * GETTER properties when accessed via Provider (e.g., multibindings with factoryRefCount > 0).
     */
    // TODO replace with AccessType
    val isProviderType: Boolean = propertyKind == PropertyKind.FIELD,
    /**
     * The switching ID for this property when using switching providers mode. IDs are unique within
     * the synchronous or suspend provider flavor and are only assigned to eligible FIELD
     * properties. Null means this property does not use a switching provider.
     */
    val switchingId: Int? = null,
  )

  data class Result(
    val initOrder: List<CollectedProperty>,
    val cachedProviderContextKeys: Set<IrContextualTypeKey>,
  )

  /**
   * Tracks factory reference counts for bindings. [factoryRefCount] is incremented when a binding
   * is accessed in a factory path context (explicit Provider/Lazy or as a dependency of something
   * in a factory path). Multiple references or repeated runtime requests need a cached factory.
   *
   * [scalarRefCount] works the same, just for scalar references. These bindings would then need a
   * property getter for sharing.
   */
  private inner class Node(
    val binding: IrBinding,
    var factoryRefCount: Int = 0,
    /** Whether a factory is requested from code that can run repeatedly after graph creation. */
    var hasRepeatedFactoryAccess: Boolean = false,
    var scalarRefCount: Int = 0,
    /** The part of [scalarRefCount] that comes from graph accessors. */
    var accessorScalarRefCount: Int = 0,
    /** Map entries reuse their providers even when only one map accessor reads them. */
    var requiresCachedProvider: Boolean = false,
  ) {
    /**
     * True when only graph accessors read this binding and they can share code. The first of those
     * accessors holds the binding's code and the others call it, so no shared getter is needed.
     */
    val isHostedByAccessor: Boolean
      get() =
        accessorsCanShareCode && factoryRefCount == 0 && scalarRefCount == accessorScalarRefCount
  }

  /**
   * Nodes tracked by canonical contextual type key. For regular bindings, this is effectively the
   * type key. For map multibindings, this preserves and distinguishes Map<K, V> from Map<K,
   * Provider<V>>.
   */
  private val nodes = HashMap<IrContextualTypeKey, Node>(INITIAL_VALUE)

  /**
   * For map multibindings, tracks which contextual variants have been accessed. This allows us to
   * generate separate properties for Map<K, V> vs Map<K, Provider<V>>. Set multibindings don't need
   * this distinction since they don't have provider value variants.
   */
  private val mapMultibindingContextKeys = HashMap<IrTypeKey, MutableSet<IrContextualTypeKey>>()

  /** Child graph property requests indexed by their canonical binding key. */
  private val reservedContextKeysByType = graph.reservedContextKeys().groupBy { it.typeKey }

  /** Cache of alias type keys to their resolved non-alias target type keys. */
  private val resolvedAliasTargets = HashMap<IrTypeKey, IrTypeKey>()

  /**
   * Tracks assisted-inject target bindings and their usage counts. Only targets used by multiple
   * Assisted bindings need FIELD properties for sharing.
   */
  private val assistedTargetCounts =
    LinkedHashMap<IrTypeKey, Pair<IrBinding.ConstructorInjected, Int>>()

  /** Counters for assigning IDs within each switching provider flavor. */
  private var nextSwitchingProviderId = 0
  private var nextSwitchingSuspendProviderId = 0

  /** Plain included accessors share a provider class when ordinary factories need their values. */
  private fun usesSharedAccessorProvider(binding: IrBinding): Boolean {
    if (metroContext.options.enableSwitchingProviders) {
      return false
    }
    if (binding !is IrBinding.GraphDependency) {
      return false
    }
    val isLocalAccessor = binding.getter != null && binding.token == null
    return isLocalAccessor && !binding.contextualTypeKey.isWrapped
  }

  /**
   * Determines if a binding is eligible for switching-provider dispatch. Returns true only for
   * FIELD properties that can be lazily instantiated via the switching provider pattern.
   *
   * Note: Deferred types (used for cycle-breaking) are eligible. While they use a delegate factory
   * for the initial field value, the setDelegate call will use the appropriate switching provider
   * when a switchingId is assigned.
   */
  private fun shouldUseSwitchingProvider(binding: IrBinding, propertyKind: PropertyKind): Boolean {
    if (propertyKind != PropertyKind.FIELD) {
      return false
    }
    if (usesSharedAccessorProvider(binding)) {
      return true
    }
    if (!metroContext.options.enableSwitchingProviders) {
      return false
    }

    return when (binding) {
      // These use specialized initialization, not switching providers
      is BoundInstance,
      is GraphDependency,
      is Alias,
      is Absent,
      is AssistedFactory,
      is GraphExtension,
      // Multibindings use specialized collection factories.
      is Multibinding,
      // Object classes cannot be scoped and are never stored in provider fields
      is ObjectClass,
      is GraphExtensionFactory -> false

      // Assisted inject factories do not implement Provider
      is ConstructorInjected if binding.isAssisted -> false

      // These can use switching providers
      is MembersInjected,
      is CustomWrapper,
      is ConstructorInjected,
      is Provided -> true
    }
  }

  private fun allocateSwitchingId(
    binding: IrBinding,
    propertyKind: PropertyKind,
  ): Int? {
    if (!shouldUseSwitchingProvider(binding, propertyKind)) return null
    return if (binding.isSuspendInGraph) {
      nextSwitchingSuspendProviderId++
    } else {
      nextSwitchingProviderId++
    }
  }

  private val IrBinding.isSuspendInGraph: Boolean
    get() = isSuspend || graph.isTransitivelySuspend(typeKey)

  // Create getters for multi-use refcounts _unless_ they have no dependencies
  private fun IrBinding.isSimpleBinding(): Boolean {
    return when (dependencies.size) {
      0 -> true
      // If it's a single parameter but the parameter is just the dispatch receiver, it's simple.
      // This is cases like a simple graph dependency or provides declaration.
      1 -> {
        when (this) {
          is GraphDependency -> true
          !is Provided -> false
          else -> parameters.dispatchReceiverParameter != null
        }
      }
      else -> false
    }
  }

  fun collect(): Result {
    val keysWithBackingProperties = mutableMapOf<IrContextualTypeKey, CollectedProperty>()

    // Roots (accessors/injectors) + keeps don't get properties themselves, but they contribute to
    // factory refcounts when they require provider instances so we mark them here.
    // This includes both direct Provider/Lazy wrapping and map types with Provider values.
    for (contextKey in roots) {
      markAccess(
        contextKey,
        isFactory = contextKey.requiresProviderInstance,
        isAccessor = contextKey !in injectorRoots,
      )
    }
    for (contextKey in extraKeeps) {
      val keptKey = cachedRequests[contextKey] ?: contextKey
      markAccess(keptKey, isFactory = keptKey.requiresProviderInstance)
    }
    for (contextKey in cachedRequests.values) {
      // Child graphs can also read this dependency after graph initialization.
      markAccess(
        contextKey,
        isFactory = contextKey.requiresProviderInstance,
        isRepeated = graph.hasReservedKey(contextKey.typeKey),
      )
    }

    fun visitKeys(keys: Collection<IrTypeKey>) {
      for (key in keys) {
        val binding = graph.findBinding(key) ?: continue

        // Skip alias bindings for refcount and dependency processing
        val shouldSkip = binding is IrBinding.Alias
        if (shouldSkip) {
          continue
        }

        // For map multibindings, process each contextual variant that was accessed
        if (binding is IrBinding.Multibinding) {
          if (binding.isSet) {
            // Set multibindings use a simple contextual key (no provider value variants)
            processBindingNode(binding, binding.contextualTypeKey, keysWithBackingProperties)
          } else {
            // Map multibindings may have multiple variants (Map<K, V> vs Map<K, Provider<V>>)
            val variants = mapMultibindingContextKeys[key]
            if (variants.isNullOrEmpty()) {
              // Nothing else depends on this multibinding, just process it plainly with no variants
              processBindingNode(binding, binding.contextualTypeKey, keysWithBackingProperties)
            } else {
              for (contextKey in variants) {
                processBindingNode(binding, contextKey, keysWithBackingProperties)
              }
            }
          }
        } else {
          // Graph dependency accessors may declare a wrapper type that differs from the binding's
          // canonical key. Property collection and lookup use the canonical key; markAccess
          // classifies requests based on whether that declared value can pass through unchanged.
          val contextKey =
            if (binding is IrBinding.GraphDependency) {
              context(metroContext) { binding.contextualTypeKey.canonicalize() }
            } else {
              binding.contextualTypeKey
            }
          processBindingNode(binding, contextKey, keysWithBackingProperties)
        }
      }
    }

    // Mark an initial visit from deferred types since they form a cycle and will appear at the end
    visitKeys(deferredTypes)

    // Single pass in reverse topological order (dependents before dependencies).
    // When we process a binding, all its dependents have already been processed,
    // so its factoryRefCount is finalized. Nodes are created lazily via getOrPut - either
    // here during iteration or earlier via markAccess when a dependent marks this binding as a
    // factory access.
    visitKeys(sortedKeys.asReversed())

    if (reachableKeys.size >= MAX_INLINE_BINDING_DEPTH) {
      BindingExpressionDepthLimiter(
          graph = graph,
          sortedKeys = sortedKeys,
          reachableKeys = reachableKeys,
          properties = keysWithBackingProperties,
          requiresProviderGetter = { contextKey ->
            val node = nodes[contextKey]
            node != null && node.factoryRefCount > 0 && node.scalarRefCount == 0
          },
        )
        .limitDepth()
    }

    // Add FIELD properties for assisted-inject targets used by multiple Assisted bindings.
    // Single-use targets don't need fields - they can be inlined.
    // Collect these separately so we can append them to init order at the end.
    // Iterate in reverse order to get forward topological order (dependencies before dependents),
    // since targets were tracked during reverse topological iteration of sortedKeys.
    val assistedTargetProperties = mutableListOf<CollectedProperty>()
    for ((_, pair) in assistedTargetCounts.entries.toList().asReversed()) {
      val (targetBinding, count) = pair
      if (count > 1) {
        val prop =
          CollectedProperty(
            binding = targetBinding,
            propertyKind = PropertyKind.FIELD,
            contextualTypeKey = targetBinding.contextualTypeKey,
            // Assisted-inject factories don't implement Provider
            isProviderType = false,
            switchingId = allocateSwitchingId(targetBinding, PropertyKind.FIELD),
          )
        keysWithBackingProperties[targetBinding.contextualTypeKey] = prop
        assistedTargetProperties += prop
      }
    }

    // Build init order: iterate sorted keys and collect any properties for reachable bindings
    // For multibindings (especially maps), there may be multiple contextual variants
    val collectedTypeKeys = keysWithBackingProperties.entries.groupBy { it.key.typeKey }
    val orderedProperties =
      buildList(keysWithBackingProperties.size) {
        for (key in sortedKeys) {
          if (key in reachableKeys) {
            collectedTypeKeys[key]?.forEach { (_, prop) -> add(prop) }
          }
        }
        // Add assisted-inject target bindings at the end.
        // These are not in sortedKeys (they're encapsulated within Assisted bindings).
        // They must come after their dependencies (which are in sortedKeys) are initialized.
        // Nothing in sortedKeys depends on them since they're not in the graph.
        addAll(assistedTargetProperties)
      }

    val cachedProviderContextKeys =
      keysWithBackingProperties.values
        .asSequence()
        .filter { it.propertyKind == PropertyKind.FIELD }
        .mapTo(mutableSetOf()) { it.contextualTypeKey }

    return Result(
      initOrder = orderedProperties,
      cachedProviderContextKeys = cachedProviderContextKeys,
    )
  }

  /**
   * Process a single binding node, determining if it needs a property and marking its dependencies.
   */
  private fun processBindingNode(
    binding: IrBinding,
    contextKey: IrContextualTypeKey,
    keysWithBackingProperties: MutableMap<IrContextualTypeKey, CollectedProperty>,
  ) {
    if (reuseBinding(contextKey)) {
      return
    }
    // Dependencies of a reused parent binding are left unreferenced here.
    val isDeferred = binding.typeKey in deferredTypes
    if (contextKey !in nodes && !isDeferred) {
      return
    }
    val node = nodes.getOrPut(contextKey) { Node(binding) }

    // Track assisted-inject target usage. Only targets used by multiple Assisted bindings
    // need FIELD properties for sharing. Single-use targets are inlined.
    if (binding is IrBinding.AssistedFactory) {
      val targetBinding = binding.targetBinding
      val targetKey = targetBinding.typeKey
      val (existingBinding, count) = assistedTargetCounts[targetKey] ?: (targetBinding to 0)
      assistedTargetCounts[targetKey] = existingBinding to (count + 1)
    }

    // Graph extensions should never have FIELD properties - they're always scalar getters or
    // inlined. If Provider access is needed, the getter call gets wrapped in InstanceFactory.
    val isGraphExtension = binding is IrBinding.GraphExtension

    // Check known property type (applies to all bindings including aliases)
    val knownPropertyType = knownPropertyType(binding, node)
    if (knownPropertyType != null) {
      val isField = knownPropertyType == PropertyKind.FIELD
      // Assisted-injected types are never factories
      val isAssistedInject = binding is IrBinding.ConstructorInjected && binding.isAssisted
      val isProviderType =
        (isField && !isAssistedInject) ||
          // For multibindings with factory refs, the property returns a Provider type
          (binding is IrBinding.Multibinding && node.factoryRefCount > 0)

      val propertyKind =
        if (isField && isProviderType) {
          providerPropertyKind(binding)
        } else {
          knownPropertyType
        }
      val switchingId = allocateSwitchingId(binding, propertyKind)

      keysWithBackingProperties[contextKey] =
        CollectedProperty(binding, propertyKind, contextKey, isProviderType, switchingId)
      // Still process dependencies even for known property types
    }

    // refcounts are finalized - check if we need a property to cache the factory
    if (contextKey !in keysWithBackingProperties) {
      // A single provider site can run repeatedly through an accessor or an unscoped consumer.
      // Cache its factory so those calls reuse it.
      val sharesFactory = node.factoryRefCount > 1 || node.hasRepeatedFactoryAccess
      val hasMixedAccess = node.factoryRefCount > 0 && node.scalarRefCount > 0
      val sharesComputedBinding = hasMixedAccess && binding !is IrBinding.BoundInstance
      val needsFactoryField = sharesFactory || sharesComputedBinding
      val needsProviderProperty = needsFactoryField || node.requiresCachedProvider
      val useField = !isGraphExtension && needsProviderProperty

      // For graph extensions, convert factory refs to scalar refs for the purpose of deciding
      // whether to create a getter property
      val effectiveScalarRefCount =
        if (isGraphExtension) node.scalarRefCount + node.factoryRefCount else node.scalarRefCount

      fun needsSharedGetter(): Boolean {
        // A child reservation needs another scalar read to share construction.
        if (effectiveScalarRefCount == 0) {
          return false
        }
        val isReadMoreThanOnce = effectiveScalarRefCount > 1
        // The first accessor holds the code and the others call it.
        val accessorHoldsCode = node.isHostedByAccessor && !isGraphExtension
        val isSharedLocally = isReadMoreThanOnce && !accessorHoldsCode
        // Child graphs can't call this graph's accessors, so they need a getter to reuse.
        val isReservedByChild = graph.hasReservedKey(binding.typeKey)
        val isShared = isSharedLocally || isReservedByChild
        return isShared && !node.binding.isSimpleBinding()
      }

      if (useField) {
        // For bound instances, the scalar instance field is created separately by IrGraphGenerator.
        // The collected FIELD here represents the cached provider wrapper, so expose it under the
        // provider context key that the generator should cache.
        val propertyContextKey =
          if (binding is IrBinding.BoundInstance) {
            context(metroContext) { contextKey.wrapInProvider() }
          } else {
            contextKey
          }
        val propertyKind = providerPropertyKind(binding)
        val switchingId = allocateSwitchingId(binding, propertyKind)
        keysWithBackingProperties[propertyContextKey] =
          CollectedProperty(
            binding,
            propertyKind,
            propertyContextKey,
            isProviderType = true,
            switchingId = switchingId,
          )
      } else if (needsSharedGetter()) {
        if (binding.isSuspendInGraph) {
          // The property returns a SuspendProvider so each consumer can await it.
          val propertyKind = providerPropertyKind(binding)
          keysWithBackingProperties[contextKey] =
            CollectedProperty(
              binding,
              propertyKind,
              contextKey,
              isProviderType = true,
              switchingId = allocateSwitchingId(binding, propertyKind),
            )
        } else {
          keysWithBackingProperties[contextKey] =
            CollectedProperty(binding, PropertyKind.GETTER, contextKey)
        }
      }
    }

    // Provider properties and direct provider requests pass providers to their dependencies.
    //
    // Graph extensions are never in a factory path - their dependencies (just the parent graph)
    // are accessed as scalar values. If Provider access is needed for the extension itself,
    // the getter call is wrapped in InstanceFactory at the call site.
    val hasFieldProperty = keysWithBackingProperties[contextKey]?.propertyKind == PropertyKind.FIELD
    val hasProviderProperty = keysWithBackingProperties[contextKey]?.isProviderType == true
    val propertyUsesFactory = hasFieldProperty || hasProviderProperty

    // MembersInjected bindings that are injector function roots (like `fun inject(target: T)`)
    // don't create MembersInjector instances - they call static inject methods directly, so their
    // dependencies are scalar accesses. Accessor roots (like `val foo: MembersInjector<T>`) DO
    // create MembersInjector instances, so they remain in factory path.
    val isInjectorFunctionRoot = binding is IrBinding.MembersInjected && contextKey in injectorRoots
    val isMembersInjectedInFactoryPath =
      binding is IrBinding.MembersInjected && !isInjectorFunctionRoot

    val hasProviderAccess = propertyUsesFactory || node.factoryRefCount > 0
    val hasMapProviders = contextKey.isMapProvider || contextKey.isMapSuspendProvider
    val hasDeferredMapValues = hasMapProviders || contextKey.isMapLazy
    val usesFactory = hasProviderAccess || hasDeferredMapValues
    val needsFactoryParameters = usesFactory || isMembersInjectedInFactoryPath
    // A shared accessor provider reads its included instance directly.
    val hasFactoryParameters = !isGraphExtension && !usesSharedAccessorProvider(binding)
    val inFactoryPath = hasFactoryParameters && needsFactoryParameters

    // Mark dependencies as factory accesses if:
    // - Explicitly Provider<T> or Lazy<T>
    // - This binding is in a factory path (factory.create() takes Provider params)
    //
    // For MembersInjected bindings with supertypes, skip dependencies that are covered by
    // supertype injectors to avoid double-counting. The supertype injectors will be processed
    // separately and their dependencies will be counted then.
    val supertypeDependencies =
      if (binding is IrBinding.MembersInjected) binding.supertypeDependencies else emptySet()

    val hasOnlyFactoryAccess = node.factoryRefCount > 0 && node.scalarRefCount == 0
    val factoryCreatedAtInitialization = hasOnlyFactoryAccess && !node.hasRepeatedFactoryAccess
    val dependenciesCreatedAtInitialization = hasFieldProperty || factoryCreatedAtInitialization

    val cacheMapValueProviders = binding is IrBinding.Multibinding && contextKey.isMapProvider
    for (dependency in binding.dependencies) {
      // Skip dependencies covered by supertype injectors
      if (dependency in supertypeDependencies) continue
      // Supertype injector nodes describe inherited parameters without creating another injector.
      val isSupertypeInjector =
        binding is IrBinding.MembersInjected && dependency in binding.supertypeMembersInjectorKeys
      val needsFactory = inFactoryPath || dependency.requiresProviderInstance
      val switchingProviderRequestsDependency =
        dependency.requiresProviderInstance &&
          shouldUseSwitchingProvider(binding, providerPropertyKind(binding))
      val hasDefaultFactoryArgument = inFactoryPath && dependency.hasDefault
      val usesCanonicalArgument = hasDefaultFactoryArgument && !switchingProviderRequestsDependency
      val dependencyKey =
        if (usesCanonicalArgument) {
          cachedRequests[dependency] ?: dependency
        } else {
          dependency
        }
      markAccess(
        dependencyKey,
        isFactory = needsFactory && !isSupertypeInjector,
        isRepeated = !dependenciesCreatedAtInitialization || switchingProviderRequestsDependency,
        requiresCachedProvider = cacheMapValueProviders,
      )
    }
  }

  private fun providerPropertyKind(binding: IrBinding): PropertyKind {
    if (!metroContext.options.forceProviderGetters) {
      return PropertyKind.FIELD
    }
    val requiresStorage = binding.isScoped() || binding.typeKey in deferredTypes
    val isAssistedFactory = binding is IrBinding.ConstructorInjected && binding.isAssisted
    return if (requiresStorage || isAssistedFactory) {
      PropertyKind.FIELD
    } else {
      PropertyKind.GETTER
    }
  }

  /**
   * Returns the property type for bindings that statically require properties, or null if the
   * binding's property requirement depends on refcount.
   */
  private fun knownPropertyType(binding: IrBinding, node: Node): PropertyKind? {
    val key = binding.typeKey

    // Deferred types always end up in DelegateFactory fields
    if (key in deferredTypes) return PropertyKind.FIELD

    // Scoped bindings always need provider fields (for DoubleCheck)
    if (binding.isScoped()) return PropertyKind.FIELD

    return when (binding) {
      is GraphDependency if (binding.token == null) -> {
        val needsAccessorProvider = usesSharedAccessorProvider(binding) && node.factoryRefCount > 0
        if (needsAccessorProvider) {
          PropertyKind.FIELD
        } else {
          graphDependencyPropertyKind(binding)
        }
      }
      // Assisted factories are stateless (they just wrap the target's MetroFactory),
      // so they don't need their own cached field. The target's MetroFactory field
      // is added separately in processBindingNode when Assisted bindings are encountered.
      // Cache shared multibinding providers while keeping scalar collections fresh.
      is Multibinding if binding.sourceBindings.isNotEmpty() -> {
        val sharesFactory = node.factoryRefCount > 1 || node.hasRepeatedFactoryAccess
        val hasMixedAccess = node.factoryRefCount > 0 && node.scalarRefCount > 0
        if (sharesFactory || hasMixedAccess) {
          return PropertyKind.FIELD
        }
        val isShared = node.factoryRefCount + node.scalarRefCount > 1 && !node.isHostedByAccessor
        // A large collection gets its own getter even with one reader. Inlining it could push a
        // reader like an init chunk or switching branch past method size limits. A lone accessor
        // reader already holds the code in its own method.
        val isLarge = binding.sourceBindings.size > metroContext.options.multibindingGetterThreshold
        val keepsGetterForSize = isLarge && !node.isHostedByAccessor
        val isReservedByChild = graph.hasReservedKey(key)
        if (isShared || keepsGetterForSize || isReservedByChild) {
          PropertyKind.GETTER
        } else {
          null
        }
      }
      // Graph extensions used by child graphs need getter properties so children can resolve
      // their property access tokens. Graph extensions are "simple" bindings (0 dependencies)
      // so they wouldn't otherwise get properties via refcount logic.
      is GraphExtension if graph.hasReservedKey(key) -> PropertyKind.GETTER
      else -> null
    }
  }

  /** Returns the property required to expose a local graph dependency to a child graph. */
  private fun graphDependencyPropertyKind(binding: IrBinding.GraphDependency): PropertyKind? {
    val reservedKeys = reservedContextKeysByType[binding.typeKey] ?: return null
    val needsField = reservedKeys.any { reservedKey ->
      reservedKey.requiresProviderInstance || !binding.canPassThrough(reservedKey)
    }
    return if (needsField) PropertyKind.FIELD else PropertyKind.GETTER
  }

  /**
   * Marks an access to a binding, tracking refcounts by canonical contextual type key. For map
   * multibindings, also records the contextual variant for later processing.
   */
  private fun markAccess(
    contextualTypeKey: IrContextualTypeKey,
    isFactory: Boolean,
    isAccessor: Boolean = false,
    isRepeated: Boolean = true,
    requiresCachedProvider: Boolean = false,
  ) {
    if (contextualTypeKey in cachedRequests) {
      return
    }
    val binding = graph.requireBinding(contextualTypeKey)

    // For aliases, resolve to the final target and mark that instead.
    val targetBinding =
      if (binding is IrBinding.Alias) {
        val aliasedType = binding.aliasedType
        if (binding.typeKey == aliasedType) {
          binding
        } else {
          val targetKey = resolveAliasTarget(aliasedType) ?: return
          graph.findBinding(targetKey) ?: return
        }
      } else {
        binding
      }

    // Strip every outer scalar wrapper from the requested key while preserving inner structure
    // such as Map<K, Provider<V>>.
    val canonicalKey =
      context(metroContext) {
        contextualTypeKey.canonicalize().withIrTypeKey(targetBinding.typeKey)
      }
    val graphDependency = targetBinding as? IrBinding.GraphDependency
    val localGraphDependency = if (graphDependency?.token == null) graphDependency else null
    val canPassThrough = localGraphDependency?.canPassThrough(contextualTypeKey) == true
    val needsAccessorProvider = isFactory && usesSharedAccessorProvider(targetBinding)
    // A Lazy accessor's handle is cached so its value is computed once. A Provider accessor can be
    // read again on each access, so it's counted like any other binding.
    val flattensDeferredGraphAccessor =
      localGraphDependency?.contextualTypeKey?.isDeferrable == true &&
        !canPassThrough &&
        localGraphDependency.contextualTypeKey.wrappedType !is WrappedType.Provider

    // For map multibindings, track the contextual variant
    if (targetBinding is IrBinding.Multibinding && !targetBinding.isSet) {
      mapMultibindingContextKeys.getAndAdd(targetBinding.typeKey, canonicalKey)
    }

    // Create node lazily if needed (the target may not have been processed yet in reverse order)
    nodes
      .getOrPut(canonicalKey) { Node(targetBinding) }
      .apply {
        if (requiresCachedProvider) {
          this.requiresCachedProvider = true
        }
        when {
          // Factory parameters need a provider even when their source parameter declares a value.
          needsAccessorProvider -> factoryRefCount++
          // Exact graph accessor values bypass generated properties.
          canPassThrough -> Unit
          // Flattening a deferred accessor reads its provider handle and initializes its scalar
          // value. Counting both lets the normal mixed-access rule select a provider field.
          flattensDeferredGraphAccessor -> {
            factoryRefCount++
            scalarRefCount++
          }
          isFactory -> {
            factoryRefCount++
            hasRepeatedFactoryAccess = hasRepeatedFactoryAccess || isRepeated
          }
          else -> {
            scalarRefCount++
            if (isAccessor) {
              accessorScalarRefCount++
            }
          }
        }
      }
  }

  /** Resolves an alias chain to its final non-alias target, caching all intermediate keys. */
  private fun resolveAliasTarget(current: IrTypeKey): IrTypeKey? {
    // Check cache
    resolvedAliasTargets[current]?.let {
      return it
    }

    val binding = graph.findBinding(current) ?: return null

    val target =
      if (binding is IrBinding.Alias) {
        val aliasedType = binding.aliasedType
        if (binding.typeKey == aliasedType) {
          current
        } else {
          resolveAliasTarget(aliasedType)
        }
      } else {
        current
      }

    // Cache on the way back up
    if (target != null) {
      resolvedAliasTargets[current] = target
    }
    return target
  }
}
