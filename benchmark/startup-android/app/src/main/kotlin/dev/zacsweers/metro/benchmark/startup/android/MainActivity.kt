// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.app.Activity
import android.net.Uri
import android.os.Bundle

class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val benchmarkApplication = application as BenchmarkApplication
    val traceUri = intent.getStringExtra(EXTRA_RUNTIME_TRACE_URI)?.let(Uri::parse)
    benchmarkApplication.runtimeTracing.setTraceUri(traceUri)
    benchmarkApplication.appGraph
    // Signal that the app is fully drawn for startup benchmarking
    reportFullyDrawn()
    // Flush the trace if we have one
    benchmarkApplication.runtimeTracing.flush()
  }

  companion object {
    const val EXTRA_RUNTIME_TRACE_URI =
      "dev.zacsweers.metro.benchmark.startup.android.RUNTIME_TRACE_URI"
  }
}
