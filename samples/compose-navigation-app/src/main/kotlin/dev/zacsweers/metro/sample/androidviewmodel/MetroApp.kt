// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.androidviewmodel

import android.app.Application
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.sample.androidviewmodel.components.AppGraph
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication

class MetroApp : Application(), MetroApplication {
  override val appComponentProviders: MetroAppComponentProviders by lazy { createGraph<AppGraph>() }
}
