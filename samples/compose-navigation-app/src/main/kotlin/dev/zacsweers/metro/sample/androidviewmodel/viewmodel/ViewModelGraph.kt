// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.androidviewmodel.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Extends
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.sample.androidviewmodel.components.AppGraph
import kotlin.reflect.KClass

@DependencyGraph(ViewModelScope::class)
interface ViewModelGraph {
  @Multibinds val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>

  @Provides
  fun provideApplication(creationExtras: CreationExtras): Application =
    creationExtras[APPLICATION_KEY]!!

  @Provides
  fun provideSavedStateHandle(creationExtras: CreationExtras): SavedStateHandle =
    creationExtras.createSavedStateHandle()

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Extends appGraph: AppGraph,
      @Provides creationExtras: CreationExtras,
    ): ViewModelGraph
  }
}
