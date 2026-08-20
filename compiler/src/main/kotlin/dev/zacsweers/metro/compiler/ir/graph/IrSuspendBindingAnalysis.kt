// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.SuspendBindingAnalysisResult
import dev.zacsweers.metro.compiler.graph.SuspendBindingRules
import dev.zacsweers.metro.compiler.graph.SuspendBindingWorklist
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import org.jetbrains.kotlin.ir.types.IrType

/**
 * Metro's IR-specific entry point for finding bindings that require suspend initialization.
 *
 * [SuspendBindingWorklist] contains the graph algorithm. This adapter supplies the IR rules for
 * recognizing suspend bindings, skipping assisted-factory dependencies, and passing an exact graph
 * dependency wrapper through without making its consumer suspend. Keeping the worklist generic also
 * lets its behavior be tested with small fake bindings instead of compiler IR.
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
  internal val rules =
    SuspendBindingRules<IrType, IrTypeKey, IrContextualTypeKey, IrBinding>(
      findBinding = findBinding,
      bindingCanPassThrough = { binding, dependency ->
        binding is IrBinding.GraphDependency && binding.canPassThrough(dependency)
      },
    )

  private val worklist =
    SuspendBindingWorklist(
      findBinding = findBinding,
      bindingIsSuspend = { it.isSuspend },
      skipDependencyTraversal = { it is IrBinding.AssistedFactory },
      rules = rules,
      currentGraphGeneration = currentGraphGeneration,
    )

  fun analyze(keys: Iterable<IrTypeKey>): Set<IrTypeKey> = worklist.analyze(keys)

  fun analyzeWithPaths(
    keys: Iterable<IrTypeKey>
  ): SuspendBindingAnalysisResult<IrTypeKey, IrContextualTypeKey> = worklist.analyzeWithPaths(keys)

  fun isSuspend(key: IrTypeKey): Boolean = worklist.isSuspend(key)
}
