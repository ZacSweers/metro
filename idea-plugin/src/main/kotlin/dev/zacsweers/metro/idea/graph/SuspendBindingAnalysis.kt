// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.graph.SuspendBindingAnalysisResult
import dev.zacsweers.metro.compiler.graph.SuspendBindingRules
import dev.zacsweers.metro.compiler.graph.SuspendBindingWorklist
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot

/** IDEA binding-model adapter for the shared suspend propagation worklist. */
internal class SuspendBindingAnalysis(findBinding: (KaTypeKey) -> KaBinding?) {
  internal val rules =
    SuspendBindingRules<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey, KaBinding>(
      findBinding = findBinding,
      bindingCanPassThrough = { binding, dependency ->
        binding is KaBinding.GraphDependency && binding.canPassThrough(dependency)
      },
    )

  private val worklist =
    SuspendBindingWorklist(
      findBinding = findBinding,
      bindingIsSuspend = { it.isSuspend },
      skipDependencyTraversal = { it is KaBinding.AssistedFactory },
      rules = rules,
      checkCanceled = ProgressManager::checkCanceled,
    )

  fun analyze(keys: Iterable<KaTypeKey>): Set<KaTypeKey> = worklist.analyze(keys)

  fun analyzeWithPaths(
    keys: Iterable<KaTypeKey>
  ): SuspendBindingAnalysisResult<KaTypeKey, KaContextualTypeKey> = worklist.analyzeWithPaths(keys)
}
