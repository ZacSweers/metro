// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.content.Context
import android.net.Uri
import androidx.tracing.AbstractTraceDriver
import androidx.tracing.AbstractTraceSink
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import androidx.tracing.wire.createPerfettoFile
import dev.zacsweers.metro.benchmark.app.component.AppComponent
import dev.zacsweers.metro.benchmark.app.component.createAndInitializeForBenchmarkTracing
import java.io.File

/**
 * Owns the optional Android runtime trace driver for startup benchmarks.
 *
 * The generated component API decides whether the tracer argument is used. Non-traced builds pass
 * `null`, which every non-traced generated component ignores. Traced builds write to external media
 * so benchmark instrumentation can copy files before test cleanup runs.
 */
class BenchmarkRuntimeTracing(private val context: Context) {
  private var traceUri: Uri? = null

  private val traceDirectory: File? =
    if (!BuildConfig.METRO_RUNTIME_TRACING) {
      null
    } else {
      context.externalMediaDirs.firstOrNull()?.resolve("metro-runtime-traces")?.apply { mkdirs() }
    }

  private val traceDriver: TraceDriver? by lazy {
    createTraceSink()?.let { sink ->
      TraceDriver(context, sink, isCategoryEnabled = { true })
    }
  }

  fun setTraceUri(uri: Uri?) {
    traceUri = uri
  }

  private fun createTraceSink(): AbstractTraceSink? {
    val uri = traceUri
    if (uri != null) {
      val outputStream = context.contentResolver.openOutputStream(uri) ?: return null
      return TraceSink(context, outputStream)
    }

    val directory = traceDirectory
    return if (directory == null) {
      null
    } else {
      TraceSink(context, traceFile = directory.createPerfettoFile())
    }
  }

  fun createAndInitializeGraph(): AppComponent {
    val driver = traceDriver
    if (driver == null) {
      return createAndInitializeForBenchmarkTracing(null)
    }

    return createAndInitializeForBenchmarkTracing(driver.tracer)
  }

  fun flush() {
    traceDriver?.flush()
  }

  fun createTraceDriver(): AbstractTraceDriver {
    return traceDriver ?: TraceDriver.getStubTraceDriver()
  }
}
