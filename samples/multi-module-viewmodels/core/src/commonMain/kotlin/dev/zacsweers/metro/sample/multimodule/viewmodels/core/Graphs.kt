// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.core

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

// These two are barebones declarations of the dependency graphs that we'll use throughout the app.
// We need to declare them separately from their annotation processing (see MetroAppGraph and
// ViewModelGraph in the app module) because we need to have visibility of all contributing Gradle
// modules when we do so.

interface AppGraph {
  val clock: Clock
  val viewModelGraphProvider: ViewModelGraphProvider
}

interface ViewModelGraph {
  @Multibinds val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>

  @Multibinds
  val assistedFactoryProviders:
    Map<KClass<out ViewModelAssistedFactory>, Provider<ViewModelAssistedFactory>>
}
