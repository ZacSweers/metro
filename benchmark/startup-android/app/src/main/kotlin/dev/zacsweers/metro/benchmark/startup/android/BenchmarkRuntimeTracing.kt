// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.content.Context
import androidx.tracing.AbstractTraceDriver
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import androidx.tracing.wire.createPerfettoFile
import dev.zacsweers.metro.benchmark.app.component.AppComponent
import dev.zacsweers.metro.benchmark.app.component.createAndInitializeForBenchmarkTracing

/**
 * Owns the optional Android runtime trace driver for startup benchmarks.
 *
 * The generated component API decides whether the tracer argument is used. Non-traced builds pass
 * `null`, which every non-traced generated component ignores. Traced builds write to the target
 * app's external media directory; the macrobenchmark flushes the AndroidX trace driver after each
 * launch, and the benchmark script pulls the finalized trace files.
 */
class BenchmarkRuntimeTracing(private val context: Context) {
  private val traceDirectory =
    if (!BuildConfig.METRO_RUNTIME_TRACING) {
      null
    } else {
      context.externalMediaDirs.firstOrNull()?.resolve("metro-runtime-traces")?.apply { mkdirs() }
    }

  private val traceDriver: TraceDriver? by lazy {
    val directory = traceDirectory
    if (directory == null) {
      null
    } else {
      val sink = TraceSink(context = context, fileProvider = { directory.createPerfettoFile() })
      TraceDriver(context, sink, isCategoryEnabled = { true })
    }
  }

  fun createAndInitializeGraph(): AppComponent {
    val driver = traceDriver
    if (driver == null) {
      return createAndInitializeForBenchmarkTracing(null)
    }

    return createAndInitializeForBenchmarkTracing(driver.tracer)
  }

  fun createTraceDriver(): AbstractTraceDriver {
    return traceDriver ?: TraceDriver.getStubTraceDriver()
  }
}
