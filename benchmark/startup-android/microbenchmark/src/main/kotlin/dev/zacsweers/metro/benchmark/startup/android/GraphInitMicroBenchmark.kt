// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup.android

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microbenchmarks for graph creation on Android.
 *
 * These benchmarks measure the time to create and initialize a dependency graph in isolation,
 * without the overhead of app startup.
 *
 * Run with: ./gradlew :startup-android:microbenchmark:connectedReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class GraphInitMicroBenchmark {

  @get:Rule val benchmarkRule: BenchmarkRule = BenchmarkRule()

  /**
   * Measures the time to create and fully initialize a dependency graph.
   *
   * The generated component entry point creates the graph and accesses all multibindings to force
   * full initialization.
   */
  @Test
  fun graphCreationAndInitialization() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val outputDirectory =
      InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
        File(it, "metro-runtime-traces")
      }
    val runtimeTracing = GraphInitRuntimeTracing(instrumentation.context, outputDirectory)
    benchmarkRule.measureRepeated { runtimeTracing.createAndInitializeGraph() }
  }
}
