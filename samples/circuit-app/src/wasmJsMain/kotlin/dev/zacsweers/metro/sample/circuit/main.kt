// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import dev.zacsweers.metro.createGraph

// TODO broken for now until https://youtrack.jetbrains.com/issue/KT-76715
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  try {
    val app = createGraph<AppGraph>().app
    CanvasBasedWindow { app() }
  } catch (ex: Throwable) {
    ex.printStackTrace()
    throw ex
  }
}
