// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup

import dev.zacsweers.metro.benchmark.app.component.createAndInitialize
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Benchmarks for Metro graph initialization (startup) performance.
 *
 * This benchmark measures the time to create and fully initialize a dependency graph, which is
 * critical for application startup time.
 *
 * Run with: ./gradlew :startup-multiplatform:benchmark
 *
 * Or for specific targets:
 * - JVM: ./gradlew :startup-multiplatform:jvmBenchmark
 * - JS: ./gradlew :startup-multiplatform:jsBenchmark
 * - WasmJS: ./gradlew :startup-multiplatform:wasmJsBenchmark
 * - Native: ./gradlew :startup-multiplatform:macosArm64Benchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = BenchmarkTimeUnit.SECONDS)
open class StartupBenchmark {

  /**
   * Measures the time to create and fully initialize a Metro dependency graph.
   *
   * This simulates a complete cold start scenario where the graph is created and all multibindings
   * are accessed, exercising the full initialization path.
   */
  @Benchmark
  fun graphCreationAndInitialization(blackhole: Blackhole) {
    val graph = createAndInitialize()
    blackhole.consume(graph)
  }
}
