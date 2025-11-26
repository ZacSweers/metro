// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelGraph
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelGraphProvider
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelGraphProviderHolder
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelScope
import kotlin.reflect.KClass
import kotlin.reflect.cast

@DependencyGraph(AppScope::class) interface AppGraph : ViewModelGraphProviderHolder

@DependencyGraph(ViewModelScope::class)
interface MetroViewModelGraph : ViewModelGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes appGraph: AppGraph, @Provides extras: CreationExtras): MetroViewModelGraph
  }
}

@Inject
@ContributesBinding(AppScope::class)
class MetroViewModelGraphProvider(private val appGraph: AppGraph) : ViewModelGraphProvider {
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
