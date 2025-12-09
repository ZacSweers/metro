// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.types.IrType

private const val INITIAL_VALUE = 512

/**
 * Computes the set of bindings that must end up in properties.
 *
 * Uses reverse topological order to correctly handle second-order effects: if a binding gets a
 * property (from refcount), it uses the factory path, which means its dependencies are accessed as
 * providers and should be counted.
 */
internal class BindingPropertyCollector(
  private val graph: IrBindingGraph,
  private val sortedKeys: List<IrTypeKey>,
  private val roots: List<IrContextualTypeKey> = emptyList(),
  private val deferredTypes: Set<IrTypeKey> = emptySet(),
) {

  data class BindingProperty(val binding: IrBinding, val propertyType: PropertyType)

  data class CollectResult(
    /** Bindings that need backing properties (fields or getters). Excludes multibindings. */
    val properties: Map<IrTypeKey, BindingProperty>,
    /** For each multibinding, the set of contextual type keys that need getter variants. */
    val multibindingAccesses: Map<IrTypeKey, Set<IrContextualTypeKey>>,
  )

  private data class Node(val binding: IrBinding, var refCount: Int = 0)

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  /** Cache of alias type keys to their resolved non-alias target type keys. */
  private val resolvedAliasTargets = HashMap<IrTypeKey, IrTypeKey>()

  /** Tracks which contextual type keys are used to access each multibinding. */
  private val multibindingAccesses = HashMap<IrTypeKey, MutableSet<IrContextualTypeKey>>()

  fun collect(): CollectResult {
    val keysWithBackingProperties = mutableMapOf<IrTypeKey, BindingProperty>()

    // Roots (accessors/injectors) don't get properties themselves, but they contribute to
    // dependency refcounts when they require provider instances so we mark them here.
    // This includes both direct Provider/Lazy wrapping and map types with Provider values.
    for (root in roots) {
      if (root.requiresProviderInstance) {
        markProviderAccess(root)
      }
      processMultibindingAccess(root, recordAccess = true)
    }

    // Single pass in reverse topological order (dependents before dependencies).
    // When we process a binding, all its dependents have already been processed,
    // so its refCount is finalized. Nodes are created lazily via getOrPut - either
    // here during iteration or earlier via markProviderAccess when a dependent
    // marks this binding as a provider access.
    for (key in sortedKeys.asReversed()) {
      val binding = graph.findBinding(key) ?: continue

      // Initialize node (may already exist from markProviderAccess)
      val node = nodes.getOrPut(key) { Node(binding) }

      // Check static property type (applies to all bindings including aliases)
      val staticPropertyType = staticPropertyType(key, binding)
      if (staticPropertyType != null) {
        keysWithBackingProperties[key] = BindingProperty(binding, staticPropertyType)
      }

      // Skip alias bindings for refcount and dependency processing
      if (binding is IrBinding.Alias) continue

      // Skip multibindings - they're handled separately via multibindingAccesses
      if (binding is IrBinding.Multibinding) continue

      // refCount is finalized - check if we need a property from refcount
      if (key !in keysWithBackingProperties && node.refCount > 1) {
        keysWithBackingProperties[key] = BindingProperty(binding, PropertyType.FIELD)
      }

      // Uses factory path if it has a property (scoped, assisted, refcount > 1) or is deferred (cycle)
      val usesFactoryPath = key in keysWithBackingProperties || key in deferredTypes

      // Mark dependencies as provider accesses if:
      // 1. Explicitly Provider<T> or Lazy<T>
      // 2. OR this binding uses factory path (factory.create() takes Provider params)
      for (dependency in binding.dependencies) {
        if (dependency.requiresProviderInstance || usesFactoryPath) {
          markProviderAccess(dependency)
        }
        // Only record multibinding access for the actual access type used at runtime:
        // - If explicitly Provider/Lazy wrapped, record (contextKey is already PROVIDER)
        // - If parent uses factory path (not explicit), skip recording - the INSTANCE
        //   contextKey would be wrong since it's actually accessed as PROVIDER
        val recordAccess = dependency.requiresProviderInstance || !usesFactoryPath
        processMultibindingAccess(dependency, recordAccess)
      }
    }

    return CollectResult(keysWithBackingProperties, multibindingAccesses)
  }

  /**
   * Returns the property type for bindings that statically require properties, or null if the
   * binding's property requirement depends on refcount.
   */
  private fun staticPropertyType(key: IrTypeKey, binding: IrBinding): PropertyType? {
    // Check reserved properties first
    graph.findAnyReservedProperty(key)?.let { reserved ->
      return when {
        reserved.property.getter != null -> PropertyType.GETTER
        reserved.property.backingField != null -> PropertyType.FIELD
        else -> reportCompilerBug("No getter or backing field for reserved property")
      }
    }

    // Scoped bindings always need provider fields (for DoubleCheck)
    if (binding.isScoped()) return PropertyType.FIELD

    return when (binding) {
      // Graph dependencies always need fields
      is IrBinding.GraphDependency -> PropertyType.FIELD
      // Assisted types always need to be a single field to ensure use of the same provider
      is IrBinding.Assisted -> PropertyType.FIELD
      // Assisted inject factories use factory path
      is IrBinding.ConstructorInjected if binding.isAssisted -> PropertyType.FIELD
      else -> null
    }
  }

  /**
   * Marks a dependency as a provider access, resolving through alias chains to mark the final
   * non-alias target.
   */
  private fun markProviderAccess(contextualTypeKey: IrContextualTypeKey) {
    val binding = graph.requireBinding(contextualTypeKey)

    // For aliases, resolve to the final target and mark that instead.
    val targetKey =
      if (binding is IrBinding.Alias && binding.typeKey != binding.aliasedType) {
        resolveAliasTarget(binding.aliasedType) ?: return
      } else {
        binding.typeKey
      }

    // Create node lazily if needed (the target may not have been processed yet in reverse order)
    val targetBinding = graph.findBinding(targetKey) ?: return
    nodes.getOrPut(targetKey) { Node(targetBinding) }.refCount++
  }

  /**
   * Processes a multibinding access:
   * 1. Optionally records the access contextual type key for getter variant generation
   * 2. If the multibinding uses Provider elements, marks all source bindings as provider accesses
   *
   * This handles:
   * - Map multibindings with Provider<V> values (e.g., `Map<Int, Provider<Int>>`)
   * - Any multibinding wrapped in Provider/Lazy (e.g., `Provider<Set<E>>`, `Lazy<Map<K, V>>`)
   *
   * @param recordAccess Whether to record this access in multibindingAccesses. Set to false when
   *   the contextKey doesn't represent the actual runtime access type (e.g., when parent uses
   *   factory path but the dependency isn't explicitly Provider-wrapped).
   */
  private fun processMultibindingAccess(contextKey: IrContextualTypeKey, recordAccess: Boolean) {
    val binding = graph.findBinding(contextKey.typeKey) as? IrBinding.Multibinding ?: return

    // Skip empty multibindings entirely - no getters or provider marking needed
    if (binding.sourceBindings.isEmpty()) return

    // Record access if requested - tracks whether INSTANCE or PROVIDER variants are needed
    if (recordAccess) {
      multibindingAccesses.getOrPut(binding.typeKey, ::mutableSetOf).add(contextKey)
    }

    // Check if this multibinding access would use Provider elements:
    // 1. Wrapped in Provider/Lazy (e.g., Provider<Set<E>>)
    // 2. Map with Provider values (e.g., Map<Int, Provider<Int>>)
    val usesProviderElements =
      contextKey.requiresProviderInstance || contextKey.wrappedType.hasProviderMapValues()

    if (usesProviderElements) {
      for (sourceKey in binding.sourceBindings) {
        markProviderAccess(IrContextualTypeKey(sourceKey))
      }
    }
  }

  /**
   * Checks if this wrapped type is a map with Provider<V> value types. For example, `Map<Int,
   * Provider<Int>>` would return true, while `Map<Int, Int>` would return false.
   */
  private fun WrappedType<IrType>.hasProviderMapValues(): Boolean {
    val mapValueType = findMapValueType() ?: return false
    return mapValueType is WrappedType.Provider
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
