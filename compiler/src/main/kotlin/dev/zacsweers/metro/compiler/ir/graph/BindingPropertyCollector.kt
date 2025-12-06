// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.reportCompilerBug

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
) {

  data class CollectedProperty(val binding: IrBinding, val propertyType: PropertyType)

  private data class Node(val binding: IrBinding, var refCount: Int = 0)

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  /** Cache of alias type keys to their resolved non-alias target type keys. */
  private val resolvedAliasTargets = HashMap<IrTypeKey, IrTypeKey>()

  fun collect(): Map<IrTypeKey, CollectedProperty> {
    val keysWithBackingProperties = mutableMapOf<IrTypeKey, CollectedProperty>()

    // TODO squish this to a single-pass
    // First pass: initialize nodes and eagerly add static property bindings
    for ((key, binding) in graph.bindingsSnapshot()) {
      nodes.getOrPut(key) { Node(binding) }

      val staticPropertyType = staticPropertyType(key, binding)
      if (staticPropertyType != null) {
        keysWithBackingProperties[key] = CollectedProperty(binding, staticPropertyType)
      }
    }

    // Second pass: reverse topological order (dependents before dependencies)
    // When we process a binding, all its dependents have already been processed,
    // so its refCount is finalized.
    for (key in sortedKeys.asReversed()) {
      val binding = graph.findBinding(key) ?: continue
      if (binding is IrBinding.Alias) continue

      val node = nodes[key] ?: continue

      // refCount is finalized - check if needs property from refcount
      if (key !in keysWithBackingProperties && node.refCount >= 2) {
        keysWithBackingProperties[key] = CollectedProperty(binding, PropertyType.FIELD)
      }

      // Uses factory path if it has a property (scoped, assisted, or refcount >= 2)
      val usesFactoryPath = key in keysWithBackingProperties

      // Mark dependencies as provider accesses if:
      // 1. Explicitly Provider<T> or Lazy<T>
      // 2. OR this binding uses factory path (factory.create() takes Provider params)
      for (dependency in binding.dependencies) {
        if (dependency.requiresProviderInstance || usesFactoryPath) {
          markProviderAccess(dependency)
        }
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
    graph.reservedProperty(key)?.let { reserved ->
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
      // Multibindings are always created adhoc, but we create their properties lazily
      is IrBinding.Multibinding -> null
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

    nodes[targetKey]?.refCount++
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
