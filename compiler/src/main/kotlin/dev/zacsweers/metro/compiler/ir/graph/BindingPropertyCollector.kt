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
    val inlineableIntoMultibinding = mutableSetOf<IrTypeKey>()

    for ((key, binding) in graph.bindingsSnapshot()) {
      // Ensure each key has a node
      nodes.getOrPut(key) { Node(binding) }

      // For non-alias bindings, mark dependencies normally.
      // For alias bindings, skip marking dependencies here - the alias's target will be
      // marked when something depends on this alias (via resolveAliasTarget below).
      if (binding !is IrBinding.Alias) {
        for (dependency in binding.dependencies) {
          dependency.mark()
        }
      }

      // Find all bindings that are directly or transitively aliased into multibindings.
      // These need properties to avoid inlining their dependency trees at the multibinding call
      // site.
      if (
        binding is IrBinding.Alias && binding.isIntoMultibinding && !binding.hasSimpleDependencies
      ) {
        resolveAliasTarget(binding.aliasedType)?.let(inlineableIntoMultibinding::add)
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
   * Marks a dependency, resolving through alias chains to mark the final non-alias target. This
   * ensures that if Foo (alias → FooImpl) is referenced N times, FooImpl gets refCount=N.
   */
  private fun IrContextualTypeKey.mark(): Boolean {
    val binding = graph.requireBinding(this)

    // For aliases, resolve to the final target and mark that instead.
    // This avoids double-counting (once via alias dependency, once via external reference)
    // while ensuring the actual implementation gets the correct ref count.
    if (binding is IrBinding.Alias && binding.typeKey != binding.aliasedType) {
      val targetKey = resolveAliasTarget(binding.aliasedType) ?: return false
      val targetBinding = graph.findBinding(targetKey) ?: return false
      val targetNode = nodes.getOrPut(targetKey) { Node(targetBinding) }
      return targetNode.mark()
    }

    return binding.mark()
  }

  private fun IrBinding.mark(): Boolean {
    val node = nodes.getOrPut(typeKey) { Node(this) }
    return node.mark()
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
