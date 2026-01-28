// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy

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
 * Multibindings are tracked by their [IrContextualTypeKey] (stripped of outer Provider/Lazy) to
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
  private val deferredTypes: Set<IrTypeKey> = emptySet(),
  /** Keys that are reachable from roots, used to filter the init order. */
  private val reachableKeys: Set<IrTypeKey> = emptySet(),
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
     * The switching ID for this property when using switching providers mode. Only assigned for
     * FIELD properties that are eligible for SwitchingProvider dispatch. Null means this property
     * does not use SwitchingProvider.
     */
    val switchingId: Int? = null,
  )

  /**
   * Tracks factory reference counts for bindings. [factoryRefCount] is incremented when a binding
   * is accessed in a factory path context (explicit Provider/Lazy or as a dependency of something
   * in a factory path). If factoryRefCount > 1, the binding needs a property to cache its factory.
   *
   * [scalarRefCount] works the same, just for scalar references. These bindings would then need a
   * property getter for sharing.
   */
  private data class Node(
    val binding: IrBinding,
    var factoryRefCount: Int = 0,
    var scalarRefCount: Int = 0,
  )

  /**
   * Nodes tracked by stripped contextual type key. For regular bindings, this is effectively the
   * type key. For map multibindings, this distinguishes Map<K, V> from Map<K, Provider<V>>.
   */
  private val nodes = HashMap<IrContextualTypeKey, Node>(INITIAL_VALUE)

  /**
   * For map multibindings, tracks which contextual variants have been accessed. This allows us to
   * generate separate properties for Map<K, V> vs Map<K, Provider<V>>. Set multibindings don't need
   * this distinction since they don't have provider value variants.
   */
  private val mapMultibindingContextKeys = HashMap<IrTypeKey, MutableSet<IrContextualTypeKey>>()

  /** Cache of alias type keys to their resolved non-alias target type keys. */
  private val resolvedAliasTargets = HashMap<IrTypeKey, IrTypeKey>()

  /**
   * Tracks assisted-inject target bindings and their usage counts. Only targets used by multiple
   * Assisted bindings need FIELD properties for sharing.
   */
  private val assistedTargetCounts =
    LinkedHashMap<IrTypeKey, Pair<IrBinding.ConstructorInjected, Int>>()

  /** Counter for assigning switching IDs in switching providers mode. */
  private var nextSwitchingId = 0

  /**
   * Determines if a binding is eligible for SwitchingProvider dispatch. Returns true only for FIELD
   * properties that can be lazily instantiated via the switching provider pattern.
   *
   * Note: Deferred types (used for cycle-breaking) are eligible for SwitchingProvider. While they
   * use DelegateFactory for the initial field value, the setDelegate call will use
   * SwitchingProvider when a switchingId is assigned.
   */
  private fun shouldUseSwitchingProvider(binding: IrBinding, propertyKind: PropertyKind): Boolean {
    if (!metroContext.options.enableSwitchingProviders) return false
    if (propertyKind != PropertyKind.FIELD) return false

    return when (binding) {
      // These use specialized initialization, not SwitchingProvider
      is BoundInstance,
      is GraphDependency,
      is Alias,
      is Absent,
      is Assisted,
      is GraphExtension,
      // Multibindings use GETTER properties, never FIELD (this check is redundant but explicit)
      is Multibinding,
      // Object classes cannot be scoped and are never stored in provider fields
      is ObjectClass,
      is GraphExtensionFactory -> false

      // Assisted inject factories do not implement Provider
      is ConstructorInjected if binding.isAssisted -> false

      // These can use SwitchingProvider
      is MembersInjected,
      is CustomWrapper,
      is ConstructorInjected,
      is Provided -> true
    }
  }

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

  fun collect(): List<CollectedProperty> {
    val keysWithBackingProperties = mutableMapOf<IrContextualTypeKey, CollectedProperty>()

    // Roots (accessors/injectors) + keeps don't get properties themselves, but they contribute to
    // factory refcounts when they require provider instances so we mark them here.
    // This includes both direct Provider/Lazy wrapping and map types with Provider values.
    for (contextKey in (roots + extraKeeps)) {
      markAccess(contextKey, isFactory = contextKey.requiresProviderInstance)
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
          // Regular bindings use a simple context key
          processBindingNode(binding, binding.contextualTypeKey, keysWithBackingProperties)
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
            // Assisted-inject factories can't use SwitchingProvider
            switchingId = null,
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

    return orderedProperties
  }

  /**
   * Process a single binding node, determining if it needs a property and marking its dependencies.
   */
  private fun processBindingNode(
    binding: IrBinding,
    contextKey: IrContextualTypeKey,
    keysWithBackingProperties: MutableMap<IrContextualTypeKey, CollectedProperty>,
  ) {
    // Initialize node (may already exist from markAccess)
    val node = nodes.getOrPut(contextKey) { Node(binding) }

    // Track assisted-inject target usage. Only targets used by multiple Assisted bindings
    // need FIELD properties for sharing. Single-use targets are inlined.
    if (binding is IrBinding.Assisted) {
      val targetBinding = binding.targetBinding
      val targetKey = targetBinding.typeKey
      val (existingBinding, count) = assistedTargetCounts[targetKey] ?: (targetBinding to 0)
      assistedTargetCounts[targetKey] = existingBinding to (count + 1)
    }

    // Graph extensions should never have FIELD properties - they're always scalar getters or
    // inlined. If Provider access is needed, the getter call gets wrapped in InstanceFactory.
    val isGraphExtension = binding is IrBinding.GraphExtension

    // Check known property type (applies to all bindings including aliases)
    val knownPropertyType = knownPropertyType(binding)
    if (knownPropertyType != null) {
      val isField = knownPropertyType == PropertyKind.FIELD
      // Assisted-injected types are never factories
      val isAssistedInject = binding is IrBinding.ConstructorInjected && binding.isAssisted
      val isProviderType =
        (isField && !isAssistedInject) ||
          // For multibindings with factory refs, the property returns a Provider type
          (binding is IrBinding.Multibinding && node.factoryRefCount > 0)

      // Assign switching ID if eligible for switching providers
      val switchingId =
        if (shouldUseSwitchingProvider(binding, knownPropertyType)) {
          nextSwitchingId++
        } else {
          null
        }

      keysWithBackingProperties[contextKey] =
        CollectedProperty(binding, knownPropertyType, contextKey, isProviderType, switchingId)
      // Still process dependencies even for known property types
    }

    // refcounts are finalized - check if we need a property to cache the factory
    if (contextKey !in keysWithBackingProperties) {
      // If we have multiple factory refs or any type has both types of refs, use a backing field
      // property (unless it's a graph extension)
      val useField =
        !isGraphExtension &&
          (node.factoryRefCount > 1 || ((node.factoryRefCount == 1) && (node.scalarRefCount >= 1)))

      // For graph extensions, convert factory refs to scalar refs for the purpose of deciding
      // whether to create a getter property
      val effectiveScalarRefCount =
        if (isGraphExtension) node.scalarRefCount + node.factoryRefCount else node.scalarRefCount

      if (useField) {
        // Assign switching ID if eligible for switching providers
        val switchingId =
          if (shouldUseSwitchingProvider(binding, PropertyKind.FIELD)) nextSwitchingId++ else null
        keysWithBackingProperties[contextKey] =
          CollectedProperty(binding, PropertyKind.FIELD, contextKey, switchingId = switchingId)
      } else if (effectiveScalarRefCount > 1 && !node.binding.isSimpleBinding()) {
        keysWithBackingProperties[contextKey] =
          CollectedProperty(binding, PropertyKind.GETTER, contextKey)
      }
    }

    // A binding is in a factory path if:
    // - It has a FIELD property (factory created at graph init for scoped/assisted/etc)
    // - It's accessed via Provider (factoryRefCount > 0, factory created inline)
    //
    // In both cases, its dependencies are accessed via Provider params in the factory.
    // Note: GETTER properties are for sharing scalar access, not factory access.
    //
    // Graph extensions are never in a factory path - their dependencies (just the parent graph)
    // are accessed as scalar values. If Provider access is needed for the extension itself,
    // the getter call is wrapped in InstanceFactory at the call site.
    val hasFieldProperty = keysWithBackingProperties[contextKey]?.propertyKind == PropertyKind.FIELD

    // MembersInjected bindings that are injector function roots (like `fun inject(target: T)`)
    // don't create MembersInjector instances - they call static inject methods directly, so their
    // dependencies are scalar accesses. Accessor roots (like `val foo: MembersInjector<T>`) DO
    // create MembersInjector instances, so they remain in factory path.
    val isInjectorFunctionRoot = binding is IrBinding.MembersInjected && contextKey in injectorRoots
    val isMembersInjectedInFactoryPath =
      binding is IrBinding.MembersInjected && !isInjectorFunctionRoot

    val inFactoryPath =
      !isGraphExtension &&
        (hasFieldProperty ||
          node.factoryRefCount > 0 ||
          contextKey.isMapProvider ||
          contextKey.isMapLazy ||
          contextKey.isMapProviderLazy ||
          isMembersInjectedInFactoryPath)

    // Mark dependencies as factory accesses if:
    // - Explicitly Provider<T> or Lazy<T>
    // - This binding is in a factory path (factory.create() takes Provider params)
    //
    // For MembersInjected bindings with supertypes, skip dependencies that are covered by
    // supertype injectors to avoid double-counting. The supertype injectors will be processed
    // separately and their dependencies will be counted then.
    val supertypeDependencies =
      if (binding is IrBinding.MembersInjected) binding.supertypeDependencies else emptySet()

    for (dependency in binding.dependencies) {
      // Skip dependencies covered by supertype injectors
      if (dependency in supertypeDependencies) continue
      markAccess(dependency, isFactory = inFactoryPath || dependency.requiresProviderInstance)
    }
  }

  /**
   * Returns the property type for bindings that statically require properties, or null if the
   * binding's property requirement depends on refcount.
   */
  private fun knownPropertyType(binding: IrBinding): PropertyKind? {
    val key = binding.typeKey

    // Deferred types always end up in DelegateFactory fields
    if (key in deferredTypes) return PropertyKind.FIELD

    // Scoped bindings always need provider fields (for DoubleCheck)
    if (binding.isScoped()) return PropertyKind.FIELD

    return when (binding) {
      // Graph dependencies always need fields, unless it's accessing a parent's property
      is GraphDependency if (binding.token == null) -> PropertyKind.FIELD
      // Assisted factories are stateless (they just wrap the target's MetroFactory),
      // so they don't need their own cached field. The target's MetroFactory field
      // is added separately in processBindingNode when Assisted bindings are encountered.
      // Non-empty multibindings get a getter
      is Multibinding if binding.sourceBindings.isNotEmpty() -> {
        PropertyKind.GETTER
      }
      // Graph extensions used by child graphs need getter properties so children can resolve
      // their property access tokens. Graph extensions are "simple" bindings (0 dependencies)
      // so they wouldn't otherwise get properties via refcount logic.
      is GraphExtension if graph.hasReservedKey(key) -> PropertyKind.GETTER
      else -> null
    }
  }

  /**
   * Marks an access to a binding, tracking refcounts by stripped contextual type key. For map
   * multibindings, also records the contextual variant for later processing.
   */
  private fun markAccess(contextualTypeKey: IrContextualTypeKey, isFactory: Boolean) {
    val binding = graph.requireBinding(contextualTypeKey)

    // For aliases, resolve to the final target and mark that instead.
    val targetBinding =
      if (binding is IrBinding.Alias && binding.typeKey != binding.aliasedType) {
        val targetKey = resolveAliasTarget(binding.aliasedType) ?: return
        graph.findBinding(targetKey) ?: return
      } else {
        binding
      }

    // Strip outer Provider/Lazy to get the normalized key for tracking
    // This preserves inner structure like Map<K, Provider<V>>
    // Strip outer Provider/Lazy from the REQUESTED key, not target binding's key
    val strippedKey =
      context(metroContext) {
        contextualTypeKey.stripOuterProviderOrLazy().withIrTypeKey(targetBinding.typeKey)
      }

    // For map multibindings, track the contextual variant
    if (targetBinding is IrBinding.Multibinding && !targetBinding.isSet) {
      mapMultibindingContextKeys.getAndAdd(targetBinding.typeKey, strippedKey)
    }

    // Create node lazily if needed (the target may not have been processed yet in reverse order)
    nodes
      .getOrPut(strippedKey) { Node(targetBinding) }
      .apply {
        if (isFactory) {
          factoryRefCount++
        } else {
          scalarRefCount++
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
      if (binding is IrBinding.Alias && binding.typeKey != binding.aliasedType) {
        resolveAliasTarget(binding.aliasedType)
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
