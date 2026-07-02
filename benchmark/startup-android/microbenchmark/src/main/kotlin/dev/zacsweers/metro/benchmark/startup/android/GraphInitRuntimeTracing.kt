// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.content.Context
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import androidx.tracing.wire.createPerfettoFile
import dev.zacsweers.metro.benchmark.app.component.createAndInitializeForBenchmarkTracing
import dev.zacsweers.metro.benchmark.startup.android.microbenchmark.BuildConfig
import java.io.File

/**
 * Owns the optional Android runtime trace driver for graph init microbenchmarks.
 *
 * Non-traced builds pass `null` to the generated component entry point. Runtime-traced Metro builds
 * pass the AndroidX tracer, flush after each measured graph initialization, and write trace files
 * where the benchmark script can pull them after the instrumentation run.
 */
class GraphInitRuntimeTracing(
  private val context: Context,
  private val outputDirectory: File?,
) {
  private val traceDirectory: File? =
    if (!BuildConfig.METRO_RUNTIME_TRACING) {
      null
    } else {
      val directory =
        outputDirectory ?: context.externalMediaDirs.firstOrNull()?.resolve("metro-runtime-traces")
      directory?.apply { mkdirs() }
    }

  private val traceDriver: TraceDriver? by lazy {
    if (traceDirectory == null) {
      null
    } else {
      val sink =
        TraceSink(context = context, fileProvider = { traceDirectory.createPerfettoFile() })
      TraceDriver(context, sink, isCategoryEnabled = { true })
    }
  }

  fun createAndInitializeGraph() {
    createAndInitializeForBenchmarkTracing(traceDriver?.tracer)
    traceDriver?.flush()
  }
}
