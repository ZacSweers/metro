// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Application
import dev.zacsweers.metro.createGraph

class MetroApp : Application() {
  /**
   * Holder reference for the app graph for
   * [dev.zacsweers.metro.sample.android.components.MetroAppComponentFactory].
   */
  val appGraph by lazy { createGraph<AppGraph>() }
}
