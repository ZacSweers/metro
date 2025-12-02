// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.reportCompilerBug

private const val INITIAL_VALUE = 512

/** Computes the set of bindings that must end up in properties. */
internal class BindingPropertyCollector(private val graph: IrBindingGraph) {

  data class CollectedProperty(val binding: IrBinding, val propertyType: PropertyType)

  private data class Node(val binding: IrBinding, var refCount: Int = 0) {
    val propertyType: PropertyType?
      get() {
        // Scoped, graph, and members injector bindings always need provider fields
        if (binding.isScoped()) return PropertyType.FIELD

        when (binding) {
          is IrBinding.GraphDependency,
          // Assisted types always need to be a single field to ensure use of the same provider
          is IrBinding.Assisted -> return PropertyType.FIELD
          is IrBinding.ConstructorInjected if binding.isAssisted -> return PropertyType.FIELD
          // Multibindings are always created adhoc, but we create their properties lazily
          is IrBinding.Multibinding -> return null
          else -> {
            // Do nothing
          }
        }

        return if (refCount >= 2) {
          // If it's unscoped but used more than once, we can generate a reusable field
          PropertyType.FIELD
        } else if (binding.isIntoMultibinding && !binding.hasSimpleDependencies) {
          // If it's into a multibinding with dependencies, extract a getter to reduce code
          // boilerplate
          PropertyType.GETTER
        } else {
          null
        }
      }

    /** @return true if we've referenced this binding before. */
    fun mark(): Boolean {
      refCount++
      return refCount > 1
    }
  }

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  /** Cache of alias type keys to their resolved non-alias target type keys. */
  private val resolvedAliasTargets = HashMap<IrTypeKey, IrTypeKey>()

  fun collect(): Map<IrTypeKey, CollectedProperty> {
    // Count references for each dependency
    val inlineableIntoMultibinding = mutableSetOf<IrTypeKey>()
    for ((key, binding) in graph.bindingsSnapshot()) {
      // Ensure each key has a node
      nodes.getOrPut(key) { Node(binding) }
      for (dependency in binding.dependencies) {
        dependency.mark()
      }

      // Find all bindings that are directly or transitively aliased into multibindings.
      // These need properties to avoid inlining their dependency trees at the multibinding call
      // site.
      val shouldCollectAliasChain =
        binding is IrBinding.Alias && binding.isIntoMultibinding && !binding.hasSimpleDependencies
      if (shouldCollectAliasChain) {
        collectAliasChain(binding.aliasedType, inlineableIntoMultibinding)
      }
    }

    // Decide which bindings actually need provider fields
    return buildMap(nodes.size) {
      for ((key, node) in nodes) {
        val propertyType =
          // If we've reserved a property for this key already, defer to that because some extension
          // is expecting it
          graph.reservedProperty(key)?.let {
            when {
              it.property.getter != null -> PropertyType.GETTER
              it.property.backingField != null -> PropertyType.FIELD
              else -> reportCompilerBug("No getter or backing field for reserved property")
            }
          }
            ?: node.propertyType
            // If no property from normal logic, but it's inlineable into a multibinding, use GETTER
            ?: if (key in inlineableIntoMultibinding) PropertyType.GETTER else continue
        put(key, CollectedProperty(node.binding, propertyType))
      }
    }
  }

  /**
   * Follows an alias chain starting from [start], invoking [action] on each binding in the chain
   * and returning the final non-alias target key.
   *
   * Handles chained aliases like: Alias1 → Alias2 → Impl. Results are cached for efficiency.
   */
  private fun followAliasChain(
    start: IrTypeKey,
    action: ((IrTypeKey, IrBinding) -> Unit)?,
  ): IrTypeKey? {
    resolvedAliasTargets[start]?.let { cached ->
      return cached
    }
    val visited = mutableSetOf<IrTypeKey>()
    val target = followAliasChainImpl(start, visited, action)
    // Cache the resolved target for all visited keys
    if (target != null) {
      for (key in visited) {
        resolvedAliasTargets[key] = target
      }
    }
    return target
  }

  private tailrec fun followAliasChainImpl(
    current: IrTypeKey,
    visited: MutableSet<IrTypeKey>,
    action: ((IrTypeKey, IrBinding) -> Unit)?,
  ): IrTypeKey? {
    if (current in visited) return null
    // Check cache - if found, we can return early without further traversal
    resolvedAliasTargets[current]?.let { cached ->
      return cached
    }
    visited += current
    val binding = graph.findBinding(current) ?: return null
    action?.invoke(current, binding)
    return if (binding is IrBinding.Alias) {
      followAliasChainImpl(binding.aliasedType, visited, action)
    } else {
      current
    }
  }

  /**
   * Follows an alias chain and collects all type keys that would be inlined. Handles chained
   * aliases like: Alias1 → Alias2 → Impl
   */
  private fun collectAliasChain(typeKey: IrTypeKey, destination: MutableSet<IrTypeKey>) {
    followAliasChain(typeKey) { key, _ -> destination += key }
  }

  private fun IrContextualTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this)
    return binding.mark()
  }

  private fun IrBinding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    val alreadyMarked = node.mark()

    // For aliases, also mark the final non-alias target in the chain.
    // This ensures that if Foo (alias) -> FooImpl is referenced twice,
    // FooImpl gets refCount >= 2 and becomes a field.
    if (this is IrBinding.Alias) {
      followAliasChain(aliasedType, action = null)?.let { targetKey ->
        val targetBinding = graph.findBinding(targetKey) ?: return@let
        val targetNode = nodes.getOrPut(targetKey) { Node(targetBinding) }
        targetNode.mark()
      }
    }

    return alreadyMarked
  }
}

private val IrBinding.hasSimpleDependencies: Boolean
  get() {
    return when (this) {
      is IrBinding.Absent -> false
      // Only one dependency that's always a field
      is IrBinding.Assisted -> true
      is IrBinding.ObjectClass -> true
      is IrBinding.BoundInstance -> true
      is IrBinding.GraphDependency -> true
      // Standard types with actual dependencies
      is IrBinding.ConstructorInjected -> dependencies.isEmpty()
      is IrBinding.Provided -> parameters.nonDispatchParameters.isEmpty()
      is IrBinding.MembersInjected -> dependencies.isEmpty()
      is IrBinding.Multibinding -> sourceBindings.isEmpty()
      // False because we don't know about the targets
      is IrBinding.Alias -> false
      is IrBinding.CustomWrapper -> false
      // TODO maybe?
      is IrBinding.GraphExtension -> false
      is IrBinding.GraphExtensionFactory -> false
    }
  }
