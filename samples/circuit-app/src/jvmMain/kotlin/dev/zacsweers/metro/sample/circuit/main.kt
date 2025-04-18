// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import dev.zacsweers.metro.createGraph

fun main() = application {
  val app = createGraph<AppGraph>().app
  Window(
    title = "Counter Circuit (Desktop)",
    state = WindowState(width = 300.dp, height = 300.dp),
    onCloseRequest = ::exitApplication,
  ) {
    app()
  }
}
