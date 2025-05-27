// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

private const val INITIAL_VALUE = 512

/** Computes the set of bindings that must end up in provider fields. */
internal class ProviderFieldCollector(private val graph: IrBindingGraph) {

  private data class Node(val binding: Binding, var refCount: Int = 0) {
    val needsField: Boolean
      get() {
        // Scoped, graph, and members injector bindings always need provider fields
        if (binding.scope != null) return true
        if (binding is Binding.GraphDependency) return true
        if (binding is Binding.MembersInjected && !binding.isFromInjectorFunction) return true
        // Multibindings are always created adhoc
        if (binding is Binding.Multibinding) return false

        // If it's unscoped but used more than once and not into a multibinding,
        // we can generate a reusable field
        if (refCount < 2) return false
        val isMultibindingProvider =
          (binding is Binding.BindingWithAnnotations) && binding.annotations.isIntoMultibinding
        return !isMultibindingProvider
      }

    /** @return true if we’ve referenced this binding before. */
    fun mark(): Boolean {
      refCount++
      return refCount > 1
    }
  }

  private val nodes = HashMap<IrTypeKey, Node>(INITIAL_VALUE)

  fun collect(): Map<IrTypeKey, Binding> {
    // Count references for each dependency
    for ((key, binding) in graph.bindingsSnapshot()) {
      // Ensure each key has a node
      nodes.getOrPut(key) { Node(binding) }
      for (dependency in binding.dependencies) {
        dependency.mark()
      }
    }

    // Decide which bindings actually need provider fields
    return buildMap(nodes.size) {
      for ((key, node) in nodes) {
        val binding = node.binding
        if (node.needsField) {
          put(key, binding)
        }
      }
    }
  }

  private fun IrContextualTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this, IrBindingStack.empty())
    return binding.mark()
  }

  private fun Binding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    return node.mark()
  }
}
