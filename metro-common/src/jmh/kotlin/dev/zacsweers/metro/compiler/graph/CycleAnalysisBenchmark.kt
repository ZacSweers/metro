// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.tracing.TraceScope
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

/**
 * Measures candidate selection and cycle diagnostics on sparse and dense graphs.
 *
 * Workload generation happens before measurement. Analysis reuses a prepared graph. Finishing
 * reuses its analysis and measures binding indexing or error rendering. Complete sealing creates a
 * fresh graph from the same bindings for each invocation.
 *
 * Build the harness with `./gradlew :metro-common:jmhJar --quiet`. The existing
 * `-Pmetro.jmh.profileGc` option adds allocation measurements when running benchmarks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class CycleAnalysisBenchmark {
  @Benchmark
  fun analyzeSparseGraph(state: SparseCycleState, blackhole: Blackhole) {
    blackhole.consume(state.graph.analyze())
  }

  @Benchmark
  fun finishSparseGraph(state: SparseCycleState, blackhole: Blackhole) {
    blackhole.consume(state.graph.finish())
  }

  @Benchmark
  fun sealSparseGraph(state: SparseCycleState, blackhole: Blackhole) {
    blackhole.consume(state.graph.seal())
  }

  @Benchmark
  fun analyzeDenseGraph(state: DenseCycleState, blackhole: Blackhole) {
    blackhole.consume(state.graph.analyze())
  }

  @Benchmark
  fun finishDenseGraph(state: DenseCycleState, blackhole: Blackhole) {
    blackhole.consume(state.graph.finish())
  }

  @Benchmark
  fun sealDenseGraph(state: DenseCycleState, blackhole: Blackhole) {
    blackhole.consume(state.graph.seal())
  }
}

/** Sparse workloads use larger vertex counts to expose repeated candidate and path searches. */
@State(Scope.Thread)
open class SparseCycleState {
  @Param("100", "1000", "10000") var size: Int = 0

  @Param("PROVIDER_RING", "EAGER_RING_WITH_DEFERRED_CHORDS", "SHARED_HUB", "SPARSE_HARD_TAIL")
  lateinit var scenario: String

  internal lateinit var graph: CycleBenchmarkGraph

  @Setup(Level.Trial)
  fun setUp() {
    graph =
      CycleBenchmarkGraph(CycleGraphWorkload.generate(CycleGraphScenario.valueOf(scenario), size))
  }
}

/** Dense workloads have separate sizes because their edge count grows with the square of size. */
@State(Scope.Thread)
open class DenseCycleState {
  @Param("16", "64", "256") var size: Int = 0

  @Param("COMPLETE_DEFERRED", "DENSE_HARD_TAIL") lateinit var scenario: String

  internal lateinit var graph: CycleBenchmarkGraph

  @Setup(Level.Trial)
  fun setUp() {
    graph =
      CycleBenchmarkGraph(CycleGraphWorkload.generate(CycleGraphScenario.valueOf(scenario), size))
  }
}

/** Owns one thread's prepared graph and checks its expected result before measurement. */
internal class CycleBenchmarkGraph(private val workload: CycleGraphWorkload) {
  private val traceScope = TraceScope.noop()
  private val prepared = prepareGraph()
  private val analysis = analyze()

  init {
    val result = finish()
    val expectedDeferredKeys = workload.expectedDeferredKeys
    if (expectedDeferredKeys != null) {
      check(result is GraphTopology<*>) { "Expected a valid ${workload.scenario} graph" }
      check(result.deferredTypes == expectedDeferredKeys) {
        "Unexpected deferred bindings for ${workload.scenario}: ${result.deferredTypes}"
      }
      check(result.sortedKeys.size == workload.size) { "The graph lost bindings during analysis" }
    }
  }

  fun analyze(): GraphAnalysis<StringTypeKey> = with(traceScope) { prepared.analyze() }

  /** Repeated valid finishes reuse the graph's binding-index storage. */
  fun finish(): Any = finish(prepared, analysis)

  fun seal(): Any {
    val freshGraph = prepareGraph()
    val freshAnalysis = with(traceScope) { freshGraph.analyze() }
    return finish(freshGraph, freshAnalysis)
  }

  private fun prepareGraph(): PreparedGraphSeal<StringTypeKey> =
    with(traceScope) { workload.newGraph().prepareSeal(roots = workload.roots) }

  /** Returns the expected cycle diagnostic and lets unrelated failures stop the benchmark. */
  private fun finish(
    graph: PreparedGraphSeal<StringTypeKey>,
    result: GraphAnalysis<StringTypeKey>,
  ): Any {
    val topology =
      try {
        with(traceScope) { graph.finish(result) }
      } catch (failure: IllegalStateException) {
        val message = failure.message
        val isCycleDiagnostic = message?.startsWith("[Metro/DependencyCycle]") == true
        if (workload.hasHardCycle && isCycleDiagnostic) {
          return checkNotNull(message)
        }
        throw failure
      }
    check(!workload.hasHardCycle) { "Expected a hard cycle for ${workload.scenario}" }
    return topology
  }
}
