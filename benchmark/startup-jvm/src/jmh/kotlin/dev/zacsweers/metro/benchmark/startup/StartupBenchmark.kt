// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup

import dev.zacsweers.metro.benchmark.app.component.createAndInitialize
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

/**
 * Benchmarks for Metro graph initialization (startup) performance.
 *
 * This benchmark measures the time to create and fully initialize a dependency graph, which is
 * critical for application startup time.
 *
 * Run with: ./gradlew :startup-jvm:jmh
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
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
