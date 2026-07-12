// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Computes which bindings transitively require suspend evaluation.
 *
 * Bindings can be added incrementally because child graphs query their parent before the parent is
 * sealed. Whenever a query discovers new bindings, the fixpoint is recomputed over everything
 * discovered so far. Final graph validation uses the same analysis with the graph's complete key
 * set.
 */
internal class SuspendBindingAnalysis(private val findBinding: (IrTypeKey) -> IrBinding?) {
  private val discoveredBindings = mutableMapOf<IrTypeKey, IrBinding>()
  private val unresolvedKeys = mutableSetOf<IrTypeKey>()
  private var suspendKeys: Set<IrTypeKey> = emptySet()

  fun analyze(keys: Iterable<IrTypeKey>): Set<IrTypeKey> {
    val pending = ArrayDeque<IrTypeKey>()
    pending.addAll(keys)
    pending.addAll(unresolvedKeys)
    unresolvedKeys.clear()

    var discoveredNewBinding = false
    while (pending.isNotEmpty()) {
      val key = pending.removeFirst()
      if (key in discoveredBindings) continue

      val binding = findBinding(key)
      if (binding == null) {
        unresolvedKeys += key
        continue
      }

      discoveredBindings[key] = binding
      discoveredNewBinding = true
      if (binding is IrBinding.AssistedFactory) continue

      for (dependency in binding.dependencies) {
        if (!stopsSuspendPropagation(dependency)) {
          pending += dependency.typeKey
        }
      }
    }

    if (discoveredNewBinding) {
      suspendKeys = computeSuspendKeys()
    }
    return suspendKeys
  }

  fun isSuspend(key: IrTypeKey): Boolean = key in analyze(listOf(key))

  private fun computeSuspendKeys(): Set<IrTypeKey> {
    val result = mutableSetOf<IrTypeKey>()
    for (binding in discoveredBindings.values) {
      if (binding.isSuspend) {
        result += binding.typeKey
      }
    }

    var changed = true
    while (changed) {
      changed = false
      for (binding in discoveredBindings.values) {
        if (binding.typeKey in result || binding is IrBinding.AssistedFactory) continue
        if (
          binding.dependencies.any { dependency ->
            !stopsSuspendPropagation(dependency) && dependency.typeKey in result
          }
        ) {
          result += binding.typeKey
          changed = true
        }
      }
    }
    return result
  }

  private fun stopsSuspendPropagation(dependency: IrContextualTypeKey): Boolean {
    if (dependency.stopsSuspendPropagation) return true
    val dependencyBinding =
      discoveredBindings[dependency.typeKey] ?: findBinding(dependency.typeKey)
    return (dependencyBinding as? IrBinding.GraphDependency)?.canPassThrough(dependency) == true
  }
}

/**
 * Whether this request evaluates without making its consumer suspend. The innermost scalar wrapper
 * controls this: outer wrappers only defer creation of the inner wrapper, while a suspend-capable
 * wrapper nearest the bound value defers the value's evaluation.
 */
internal val IrContextualTypeKey.stopsSuspendPropagation: Boolean
  get() = wrappedType.usesSuspendProvider() == true || isMapSuspendProvider
