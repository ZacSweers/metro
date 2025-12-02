// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import androidx.compose.ui.window.singleWindowApplication
import dev.zacsweers.metro.createGraph

fun main() {
  val appGraph = createGraph<AppGraph>()

  singleWindowApplication(title = "Compose ViewModels Sample") {
    ComposeApp(appGraph.metroViewModelFactory)
  }
}
