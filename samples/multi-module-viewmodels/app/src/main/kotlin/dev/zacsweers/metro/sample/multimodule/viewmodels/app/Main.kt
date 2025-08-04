// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.singleWindowApplication
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.LocalViewModelGraphProvider
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.formatNow

fun main() {
  // Accessing the Clock instance directly from our AppGraph. This will also be used in the
  // ViewModelScope from each of the two screens.
  val appGraph = createGraph<MetroAppGraph>()
  val time = appGraph.clock.formatNow()

  singleWindowApplication(title = "Sample App - started at $time") {
    CompositionLocalProvider(LocalViewModelGraphProvider provides appGraph.viewModelGraphProvider) {
      ComposeApp()
    }
  }
}
