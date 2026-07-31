// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.SuspendBindingWorklist
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Metro's IR-specific entry point for finding bindings that require suspend initialization.
 *
 * [SuspendBindingWorklist] contains the graph algorithm. This adapter supplies the IR rules for
 * recognizing suspend bindings, skipping assisted-factory dependencies, and passing an exact graph
 * dependency wrapper through without making its consumer suspend.
 *
 * Child graphs can query their parent before the parent graph is sealed. The same analysis instance
 * therefore accepts more bindings as the parent grows and updates its earlier answers. Final graph
 * validation uses these rules after the full binding set is available.
 *
 * Reading a multibinding's dependencies freezes its current set of contributions. The graph
 * prevents later contributions from being added, so the dependency edges cached here remain valid.
 */
internal class SuspendBindingAnalysis(
  findBinding: (IrTypeKey) -> IrBinding?,
  currentGraphGeneration: () -> Int = { 0 },
) {
  private val worklist =
    SuspendBindingWorklist(
      findBinding = findBinding,
      bindingIsSuspend = { it.isSuspend },
      skipDependencyTraversal = { it is IrBinding.AssistedFactory },
      canPassThrough = { binding, dependency ->
        binding is IrBinding.GraphDependency && binding.canPassThrough(dependency)
      },
      currentGraphGeneration = currentGraphGeneration,
    )

  fun analyze(keys: Iterable<IrTypeKey>): Set<IrTypeKey> = worklist.analyze(keys)

  fun isSuspend(key: IrTypeKey): Boolean = worklist.isSuspend(key)
}

/**
 * Whether this request defers initialization and therefore stops suspend propagation.
 *
 * Validation separately rejects synchronous `Provider` or `Lazy` wrappers over a suspend binding.
 * That invalid request must not also make its consumer transitively suspend.
 */
internal val IrContextualTypeKey.stopsSuspendPropagation: Boolean
  get() = isDeferrable

/**
 * Whether this request stops suspend propagation because it defers initialization or because a
 * graph dependency can pass the exact wrapper value through.
 *
 * Shared by graph validation and child queries against an unsealed parent graph.
 */
internal fun IrContextualTypeKey.stopsSuspendPropagation(
  findBinding: (IrTypeKey) -> IrBinding?
): Boolean {
  if (stopsSuspendPropagation) return true
  return (findBinding(typeKey) as? IrBinding.GraphDependency)?.canPassThrough(this) == true
}

/**
 * Whether this dependency makes its consumer suspend: the requested binding is suspend and this
 * request does not defer or pass it through.
 *
 * Shared by graph validation and code generation so both use the same propagation rules.
 */
internal fun IrContextualTypeKey.propagatesSuspend(
  isSuspendKey: (IrTypeKey) -> Boolean,
  findBinding: (IrTypeKey) -> IrBinding?,
): Boolean = isSuspendKey(typeKey) && !stopsSuspendPropagation(findBinding)
