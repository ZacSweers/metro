// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Computes which bindings transitively require suspend resolution.
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
        if (!dependency.stopsSuspendPropagation) {
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
            !dependency.stopsSuspendPropagation && dependency.typeKey in result
          }
        ) {
          result += binding.typeKey
          changed = true
        }
      }
    }
    return result
  }
}

/**
 * Whether this request resolves without making its consumer suspend. Ordinary Provider/Lazy
 * wrappers are deliberately excluded: they are invalid over suspend bindings and are diagnosed
 * after propagation.
 */
private val IrContextualTypeKey.stopsSuspendPropagation: Boolean
  get() = isWrappedInSuspendProvider || isWrappedInSuspendLazy || isMapSuspendProvider
