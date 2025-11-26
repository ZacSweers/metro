// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.core

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

/**
 * Base interface for the app-level dependency graph. Contains the [ViewModelGraphProvider] that can
 * be used to create ViewModelGraph instances.
 */
interface ViewModelGraphProviderHolder {
  val viewModelGraphProvider: ViewModelGraphProvider
}

/**
 * Base interface for the ViewModel-scoped dependency graph. Contains the multibindings for both
 * standard ViewModels and assisted ViewModels.
 */
interface ViewModelGraph {
  @Multibinds val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>

  @Multibinds
  val assistedFactoryProviders:
    Map<KClass<out ViewModelAssistedFactory>, Provider<ViewModelAssistedFactory>>
}
