// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

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

        if (binding.isIntoMultibinding) {
          return PropertyType.GETTER
        }

        // If it's unscoped but used more than once and not into a multibinding,
        // we can generate a reusable field
        return if (refCount >= 2) {
          PropertyType.FIELD
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

  fun collect(): Map<IrTypeKey, CollectedProperty> {
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
        val propertyType = node.propertyType ?: continue
        put(key, CollectedProperty(node.binding, propertyType))
      }
    }
  }

  private fun IrContextualTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this)
    return binding.mark()
  }

  private fun IrBinding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    return node.mark()
  }
}
