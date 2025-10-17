// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.androidviewmodel

import android.app.Application
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.sample.androidviewmodel.components.AppGraph

class MetroApp : Application() {
  /** Holder reference for the app graph for [MetroAppComponentFactory]. */
  val appGraph by lazy { createGraph<AppGraph>() }
}
