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
 * Uses reverse topological order to correctly handle second-order effects: if a binding is in a
 * factory path, its dependencies are accessed as providers and should be counted.
 *
 * A binding is in a "factory path" when its factory is created (either cached in a property or
 * inline). This happens when:
 * 1. The binding has a property (scoped, assisted, or factoryRefCount > 1)
 * 2. The binding is accessed via Provider/Lazy (factoryRefCount > 0)
 */
internal class BindingPropertyCollector(
  private val graph: IrBindingGraph,
  private val sortedKeys: List<IrTypeKey>,
  private val roots: List<IrContextualTypeKey> = emptyList(),
) {

  data class CollectedProperty(val binding: IrBinding, val propertyType: PropertyType)

  /**
   * Tracks factory reference counts for bindings. [factoryRefCount] is incremented when a binding
   * is accessed in a factory path context (explicit Provider/Lazy or as a dependency of something
   * in a factory path). If factoryRefCount > 1, the binding needs a property to cache its factory.
   */
  private data class Node(val binding: IrBinding, var factoryRefCount: Int = 0)

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  /** Cache of alias type keys to their resolved non-alias target type keys. */
  private val resolvedAliasTargets = HashMap<IrTypeKey, IrTypeKey>()

  fun collect(): Map<IrTypeKey, CollectedProperty> {
    val keysWithBackingProperties = mutableMapOf<IrTypeKey, CollectedProperty>()

    // Roots (accessors/injectors) don't get properties themselves, but they contribute to
    // factory refcounts when they require provider instances so we mark them here.
    // This includes both direct Provider/Lazy wrapping and map types with Provider values.
    for (root in roots) {
      if (root.requiresProviderInstance) {
        markFactoryAccess(root)
      }
      maybeMarkMultibindingSourcesAsFactoryAccess(
        root,
        inFactoryPath = root.requiresProviderInstance,
      )
    }

    // Single pass in reverse topological order (dependents before dependencies).
    // When we process a binding, all its dependents have already been processed,
    // so its factoryRefCount is finalized. Nodes are created lazily via getOrPut - either
    // here during iteration or earlier via markFactoryAccess when a dependent
    // marks this binding as a factory access.
    for (key in sortedKeys.asReversed()) {
      val binding = graph.findBinding(key) ?: continue

      // Initialize node (may already exist from markFactoryAccess)
      val node = nodes.getOrPut(key) { Node(binding) }

      // Check static property type (applies to all bindings including aliases)
      val staticPropertyType = staticPropertyType(key, binding)
      if (staticPropertyType != null) {
        keysWithBackingProperties[key] = CollectedProperty(binding, staticPropertyType)
      }

      // Skip alias bindings for refcount and dependency processing
      if (binding is IrBinding.Alias) continue

      // Multibindings are always created adhoc and don't get properties
      if (binding is IrBinding.Multibinding) continue

      // factoryRefCount is finalized - check if we need a property to cache the factory
      if (key !in keysWithBackingProperties && node.factoryRefCount > 1) {
        keysWithBackingProperties[key] = CollectedProperty(binding, PropertyType.FIELD)
      }

      // A binding is in a factory path if:
      // - It has a property (factory created at graph init)
      // - t's accessed via Provider (factoryRefCount > 0, factory created inline)
      //
      // In both cases, its dependencies are accessed via Provider params in the factory.
      val inFactoryPath = key in keysWithBackingProperties || node.factoryRefCount > 0

      // Mark dependencies as factory accesses if:
      // - Explicitly Provider<T> or Lazy<T>
      // - This binding is in a factory path (factory.create() takes Provider params)
      for (dependency in binding.dependencies) {
        if (dependency.requiresProviderInstance || inFactoryPath) {
          markFactoryAccess(dependency)
        }
        maybeMarkMultibindingSourcesAsFactoryAccess(dependency, inFactoryPath = inFactoryPath)
      }
    }

    return keysWithBackingProperties
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
   * Marks a dependency as a factory access, resolving through alias chains to mark the final
   * non-alias target. Increments the target's factoryRefCount.
   */
  private fun markFactoryAccess(contextualTypeKey: IrContextualTypeKey) {
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
    nodes.getOrPut(targetKey) { Node(targetBinding) }.factoryRefCount++
  }

  /**
   * If the given contextual type key corresponds to a multibinding that would use Provider
   * elements, marks all its source bindings as factory accesses. This handles:
   * - Map multibindings with Provider<V> values (e.g., `Map<Int, Provider<Int>>`)
   * - Any multibinding wrapped in Provider/Lazy (e.g., `Provider<Set<E>>`, `Lazy<Map<K, V>>`)
   * - Multibindings accessed in a factory path (the factory takes Provider<Multibinding> as a
   *   param)
   */
  private fun maybeMarkMultibindingSourcesAsFactoryAccess(
    contextKey: IrContextualTypeKey,
    inFactoryPath: Boolean = false,
  ) {
    val binding = graph.findBinding(contextKey.typeKey) as? IrBinding.Multibinding ?: return

    // Check if this multibinding access would use Provider elements:
    // 1. Wrapped in Provider/Lazy (e.g., Provider<Set<E>>)
    // 2. Map with Provider values (e.g., Map<Int, Provider<Int>>)
    // 3. Accessed in a factory path (the factory takes Provider<Multibinding> as a param)
    val usesProviderElements =
      contextKey.requiresProviderInstance ||
        inFactoryPath ||
        contextKey.wrappedType.hasProviderMapValues()

    if (usesProviderElements) {
      for (sourceKey in binding.sourceBindings) {
        markFactoryAccess(IrContextualTypeKey(sourceKey))
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
