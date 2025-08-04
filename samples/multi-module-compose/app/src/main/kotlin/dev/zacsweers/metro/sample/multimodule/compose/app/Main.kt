// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalTime::class)

package dev.zacsweers.metro.sample.multimodule.compose.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.singleWindowApplication
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.sample.multimodule.compose.core.LocalAppGraph
import dev.zacsweers.metro.sample.multimodule.compose.core.LocalViewModelGraphProvider
import dev.zacsweers.metro.sample.multimodule.compose.core.formatNow
import kotlin.time.ExperimentalTime

fun main() {
  val appGraph = createGraph<MetroAppGraph>()
  val time = appGraph.clock.formatNow()

  singleWindowApplication(title = "Sample App - started at $time") {
    CompositionLocalProvider(
      LocalViewModelGraphProvider provides appGraph.viewModelGraphProvider,
      LocalAppGraph provides appGraph,
    ) {
      ComposeApp()
    }
  }
}
