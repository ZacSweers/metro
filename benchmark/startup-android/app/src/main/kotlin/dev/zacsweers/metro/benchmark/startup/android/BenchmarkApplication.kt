// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.app.Application
import androidx.tracing.AbstractTraceDriver
import dev.zacsweers.metro.benchmark.app.component.AppComponent

class BenchmarkApplication : Application(), AbstractTraceDriver.Factory<AbstractTraceDriver> {
  val runtimeTracing by lazy { BenchmarkRuntimeTracing(this) }

  val appGraph: AppComponent by lazy { runtimeTracing.createAndInitializeGraph() }

  override fun create(): AbstractTraceDriver {
    return runtimeTracing.createTraceDriver()
  }
}
