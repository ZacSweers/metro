// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.app.Application
import dev.zacsweers.metro.benchmark.app.component.createAndInitialize

class BenchmarkApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // Initialize the Metro dependency graph
    createAndInitialize()
  }
}
