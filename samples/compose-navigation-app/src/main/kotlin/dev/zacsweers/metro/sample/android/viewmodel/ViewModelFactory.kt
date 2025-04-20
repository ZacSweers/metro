// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metro.sample.android.components.AppGraph
import kotlin.reflect.KClass

/**
 * A [ViewModelProvider.Factory] that uses an injected map of [KClass] to [Provider] of [ViewModel]
 * to create ViewModels.
 */
@ContributesBinding(AppScope::class)
@Inject
class MetroViewModelFactory(val appGraph: AppGraph) : ViewModelProvider.Factory {

  override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
    val viewModelGraph = createGraphFactory<ViewModelGraph.Factory>().create(appGraph, extras)

    val provider =
      viewModelGraph.viewModelProviders[modelClass.kotlin]
        ?: throw IllegalArgumentException("Unknown model class $modelClass")

    return modelClass.cast(provider())
  }
}
