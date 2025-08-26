// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.app

import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.AppGraph
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.ViewModelGraph
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.ViewModelScope

@DependencyGraph(AppScope::class) interface MetroAppGraph : AppGraph

@DependencyGraph(ViewModelScope::class)
interface MetroViewModelGraph : ViewModelGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes appGraph: MetroAppGraph,
      @Provides extras: CreationExtras,
    ): MetroViewModelGraph
  }
}
