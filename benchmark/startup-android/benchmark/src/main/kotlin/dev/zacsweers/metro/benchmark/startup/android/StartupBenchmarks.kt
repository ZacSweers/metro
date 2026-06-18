// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import android.content.ClipData
import android.content.Intent
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks for measuring app startup time with Metro DI.
 *
 * This benchmark measures the entire app startup process, including Metro graph creation and
 * initialization via `createAndInitialize()`, which provides a realistic view of Metro's impact on
 * user-perceived startup time.
 *
 * Run with: ./gradlew :startup-android:benchmark:connectedBenchmarkAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  /**
   * Measures cold startup time.
   *
   * Cold startup is when the app is launched after being killed or for the first time after boot.
   * This includes all class loading, Metro graph creation via `createAndInitialize()`, and app
   * initialization.
   */
  @Test
  fun startup() {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(StartupTimingMetric()),
      iterations = 10,
      startupMode = StartupMode.COLD,
    ) {
      pressHome()
      startActivityAndWait(runtimeTracedLaunchIntent())
      flushRuntimeTraces()
    }
  }

  private fun runtimeTracedLaunchIntent(): Intent {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val traceUri = RuntimeTraceFileProvider.uriFor("perfetto-${System.nanoTime()}.perfetto-trace")
    val intent =
      instrumentation
        .context
        .packageManager
        .getLaunchIntentForPackage(PACKAGE_NAME)
        ?: error("Could not resolve launcher intent for $PACKAGE_NAME")
    intent.putExtra(EXTRA_RUNTIME_TRACE_URI, traceUri.toString())
    intent.clipData =
      ClipData.newUri(instrumentation.context.contentResolver, "metro-runtime-trace", traceUri)
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    return intent
  }

  /** Flushes the target app's AndroidX trace driver before UTP copies additional output. */
  private fun flushRuntimeTraces() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.executeShellCommand(
      "am broadcast -a $TRACE_FLUSH_ACTION $PACKAGE_NAME/$TRACE_RECEIVER"
    )
  }

  companion object {
    private const val PACKAGE_NAME = "dev.zacsweers.metro.benchmark.startup.android"
    private const val EXTRA_RUNTIME_TRACE_URI =
      "dev.zacsweers.metro.benchmark.startup.android.RUNTIME_TRACE_URI"
    private const val TRACE_FLUSH_ACTION = "androidx.tracing.profiler.action.FLUSH_TRACES_GET_PATH"
    private const val TRACE_RECEIVER = "androidx.tracing.profiler.ConnectedProfilerTracingReceiver"
  }
}
