// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.app.Application
import androidx.tracing.AbstractTraceDriver
import dev.zacsweers.metro.benchmark.app.component.AppComponent
import dev.zacsweers.metro.benchmark.app.component.createAndInitialize

class BenchmarkApplication : Application(), AbstractTraceDriver.Factory {
  val runtimeTracing by lazy { BenchmarkRuntimeTracing(this) }

  lateinit var appGraph: AppComponent
    private set

  override fun onCreate() {
    super.onCreate()
    appGraph =
      if (BuildConfig.METRO_RUNTIME_TRACING) {
        runtimeTracing.createAndInitializeGraph()
      } else {
        createAndInitialize()
      }
  }

  override fun create(): AbstractTraceDriver {
    return runtimeTracing.createTraceDriver()
  }
}
