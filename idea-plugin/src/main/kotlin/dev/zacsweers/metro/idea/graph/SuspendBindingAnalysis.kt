// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import dev.zacsweers.metro.compiler.graph.SuspendBindingWorklist
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaTypeKey

/** IDEA binding-model adapter for the shared suspend propagation worklist. */
internal class SuspendBindingAnalysis(findBinding: (KaTypeKey) -> KaBinding?) {
  private val worklist =
    SuspendBindingWorklist(
      findBinding = findBinding,
      bindingIsSuspend = { it.isSuspend },
      skipDependencyTraversal = { it is KaBinding.AssistedFactory },
      canPassThrough = { binding, dependency ->
        binding is KaBinding.GraphDependency && binding.canPassThrough(dependency)
      },
    )

  fun analyze(keys: Iterable<KaTypeKey>): Set<KaTypeKey> = worklist.analyze(keys)
}
