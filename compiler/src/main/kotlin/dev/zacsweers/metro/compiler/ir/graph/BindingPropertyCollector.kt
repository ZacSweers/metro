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
        val propertyType =
          // If we've reserved a property for this key already, defer to that because some extension
          // is expecting it
          graph.reservedProperty(key)?.let {
            when {
              it.property.getter != null -> PropertyType.GETTER
              it.property.backingField != null -> PropertyType.FIELD
              else -> reportCompilerBug("No getter or backing field for reserved property")
            }
          } ?: node.propertyType ?: continue
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
