// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
      startActivityAndWait()
    }
  }

  companion object {
    private const val PACKAGE_NAME = "dev.zacsweers.metro.benchmark.startup.android"
  }
}
