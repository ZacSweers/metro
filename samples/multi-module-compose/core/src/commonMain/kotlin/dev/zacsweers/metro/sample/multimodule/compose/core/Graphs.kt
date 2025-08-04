// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalTime::class)

package dev.zacsweers.metro.sample.multimodule.compose.core

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
