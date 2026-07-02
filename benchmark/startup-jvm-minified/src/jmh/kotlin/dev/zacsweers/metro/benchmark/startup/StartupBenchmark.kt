// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.benchmark.startup

import dev.zacsweers.metro.benchmark.app.component.createAndInitialize
import dev.zacsweers.metro.benchmark.app.component.traceNextCreateAndInitialize
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType

/**
 * Benchmarks for Metro graph initialization (startup) performance with R8-minified classes.
 *
 * This benchmark measures the time to create and fully initialize a dependency graph using
 * R8-optimized/minified code, simulating production Android builds.
 *
 * Run with: ./gradlew :startup-jvm-minified:jmh
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class StartupBenchmark {
  private var runtimeTraceCaptured = false

  /**
   * Requests exactly one runtime trace from the generated component during measurement.
   *
   * Runtime tracing is diagnostic output, not part of the benchmark score. Waiting for the first
   * measurement iteration keeps warmup noise out of the Perfetto file while still capturing a
   * representative graph initialization.
   */
  @Setup(Level.Iteration)
  fun runtimeTraceSetup(iterationParams: IterationParams) {
    if (runtimeTraceCaptured) {
      return
    }
    if (iterationParams.type != IterationType.MEASUREMENT) {
      return
    }
    traceNextCreateAndInitialize()
    runtimeTraceCaptured = true
  }

  /**
   * Measures the time to create and fully initialize a Metro dependency graph.
   *
   * This simulates a complete cold start scenario where the graph is created and all multibindings
   * are accessed, exercising the full initialization path with R8-minified classes.
   */
  @Benchmark
  fun graphCreationAndInitialization(blackhole: Blackhole) {
    val graph = createAndInitialize()
    blackhole.consume(graph)
  }
}
