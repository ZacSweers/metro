// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.ViewModelGraph
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.ViewModelGraphProvider
import kotlin.reflect.KClass
import kotlin.reflect.cast

@Inject
@ContributesBinding(AppScope::class)
class MetroViewModelGraphProvider(private val appGraph: MetroAppGraph) : ViewModelGraphProvider {
  override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
    val viewModelGraph = buildViewModelGraph(extras)
    val viewModelProvider =
      requireNotNull(viewModelGraph.viewModelProviders[modelClass]) {
        "Unknown model class $modelClass"
      }
    return modelClass.cast(viewModelProvider())
  }

  override fun buildViewModelGraph(extras: CreationExtras): ViewModelGraph =
    createGraphFactory<MetroViewModelGraph.Factory>().create(appGraph, extras)
}
