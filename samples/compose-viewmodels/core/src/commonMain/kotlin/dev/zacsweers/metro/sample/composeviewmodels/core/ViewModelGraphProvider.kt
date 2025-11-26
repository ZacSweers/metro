// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.core

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/** Factory for creating [ViewModelGraph] instances with the given [CreationExtras]. */
fun interface ViewModelGraphProvider : ViewModelProvider.Factory {
  fun buildViewModelGraph(extras: CreationExtras): ViewModelGraph
}

/**
 * CompositionLocal for providing the [ViewModelGraphProvider] to the Compose tree. This allows
 * [metroViewModel] and [assistedMetroViewModel] to access the provider.
 */
val LocalViewModelGraphProvider =
  staticCompositionLocalOf<ViewModelGraphProvider> { error("No ViewModelGraphProvider registered") }
