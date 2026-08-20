// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.tracing.TraceScope
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
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
 * Measures Metro's shared graph algorithms without running the compiler or an IDE.
 *
 * Run with `./gradlew :metro-common:jmh --quiet`.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class GraphAnalysisBenchmark {

  @Param("100", "1000", "10000") var size: Int = 0

  @Param("CHAIN", "FAN_OUT", "DIAMOND") lateinit var topology: String

  private lateinit var keys: List<StringTypeKey>
  private lateinit var contextKeys: List<StringContextualTypeKey>
  private lateinit var bindings: List<StringBinding>
  private lateinit var deferredBindings: List<StringBinding>
  private lateinit var bindingsByKey: Map<StringTypeKey, StringBinding>
  private lateinit var deferredBindingsByKey: Map<StringTypeKey, StringBinding>
  private lateinit var suspendSourceKeys: Set<StringTypeKey>
  private lateinit var roots: Map<StringContextualTypeKey, StringBindingStack.Entry>
  private lateinit var rootEntries: List<StringBindingStack.Entry>
  private lateinit var optionalRootEntries: List<StringBindingStack.Entry>
  private lateinit var contributionIds: Set<ClassId>
  private lateinit var sparseReplacements: Map<ClassId, Set<ClassId>>

  @Setup(Level.Trial)
  fun setUp() {
    keys = List(size) { index -> StringTypeKey("Node$index") }
    contextKeys = keys.map { StringContextualTypeKey.create(it) }
    rootEntries = contextKeys.map { StringBindingStack.Entry(it) }
    optionalRootEntries = keys.map { key ->
      StringBindingStack.Entry(StringContextualTypeKey.create(key, hasDefault = true))
    }
    bindings = keys.mapIndexed { index, key ->
      StringBinding(key, dependencyIndexes(index).map { contextKeys[it] })
    }
    bindingsByKey = bindings.associateBy { it.typeKey }
    suspendSourceKeys = keys.filterIndexedTo(hashSetOf()) { index, _ -> index % 64 == 0 }
    deferredBindings = keys.mapIndexed { index, key ->
      StringBinding(
        key,
        dependencyIndexes(index).map { dependencyIndex ->
          if (index % 8 == 0) {
            StringContextualTypeKey.create(StringTypeKey("() -> Node$dependencyIndex"))
          } else {
            contextKeys[dependencyIndex]
          }
        },
      )
    }
    deferredBindingsByKey = deferredBindings.associateBy { it.typeKey }
    roots = linkedMapOf(contextKeys.last() to rootEntries.last())

    contributionIds =
      keys.mapTo(linkedSetOf()) { key -> ClassId.topLevel(FqName("benchmark.${key.type}")) }
    val contributionList = contributionIds.toList()
    sparseReplacements =
      contributionList.indices
        .filter { index -> index > 0 && index % 64 == 0 }
        .associate { index -> contributionList[index] to setOf(contributionList[index - 1]) }
  }

  @Benchmark
  fun sealGraph(blackhole: Blackhole) {
    val graph = newGraph()
    val stack = StringBindingStack("BenchmarkGraph")
    for (binding in bindings) {
      graph.tryPut(binding, stack)
    }
    val result = with(TraceScope.noop()) { graph.seal(roots = roots, shrinkUnusedBindings = false) }
    blackhole.consume(result)
  }

  @Benchmark
  fun insertUniqueRoots(blackhole: Blackhole) {
    val result = LinkedHashMap<StringContextualTypeKey, StringBindingStack.Entry>(size)
    for (entry in rootEntries) {
      result.putGraphRoot(entry.contextKey, entry)
    }
    blackhole.consume(result)
  }

  @Benchmark
  fun replaceOptionalRoots(blackhole: Blackhole) {
    val result = LinkedHashMap<StringContextualTypeKey, StringBindingStack.Entry>(size)
    for (index in keys.indices) {
      val optional = optionalRootEntries[index]
      result.putGraphRoot(optional.contextKey, optional)
      val required = rootEntries[index]
      result.putGraphRoot(required.contextKey, required)
    }
    blackhole.consume(result)
  }

  @Benchmark
  fun propagateWithoutSuspendSources(blackhole: Blackhole) {
    blackhole.consume(newWorklist(hasSuspendSources = false).analyze(keys))
  }

  @Benchmark
  fun propagateSparseSuspendSources(blackhole: Blackhole) {
    blackhole.consume(newWorklist(hasSuspendSources = true).analyze(keys))
  }

  @Benchmark
  fun propagateAcrossDeferredBoundaries(blackhole: Blackhole) {
    blackhole.consume(
      newWorklist(hasSuspendSources = true, useDeferredBoundaries = true).analyze(keys)
    )
  }

  @Benchmark
  fun mergeWithoutReplacements(blackhole: Blackhole) {
    blackhole.consume(computeMergePlan(contributionIds, excluded = emptySet()) { emptySet() })
  }

  @Benchmark
  fun mergeWithSparseReplacements(blackhole: Blackhole) {
    blackhole.consume(
      computeMergePlan(contributionIds, excluded = emptySet()) { id ->
        sparseReplacements[id].orEmpty()
      }
    )
  }

  private fun dependencyIndexes(index: Int): List<Int> {
    if (index == 0) return emptyList()
    return when (topology) {
      "CHAIN" -> listOf(index - 1)
      "FAN_OUT" -> {
        val first = index / 2
        if (first == 0) listOf(0) else listOf(first, first - 1)
      }
      "DIAMOND" -> {
        val first = index - 1
        if (index < 2) listOf(first) else listOf(first, index - 2)
      }
      else -> error("Unknown topology: $topology")
    }
  }

  private fun newGraph(): StringGraph {
    return StringGraph(
      newBindingStack = { StringBindingStack("BenchmarkGraph") },
      newBindingStackEntry = { key, _, _ -> StringBindingStack.Entry(key) },
    )
  }

  private fun newWorklist(
    hasSuspendSources: Boolean,
    useDeferredBoundaries: Boolean = false,
  ): SuspendBindingWorklist<String, StringTypeKey, StringContextualTypeKey, StringBinding> {
    val indexedBindings = if (useDeferredBoundaries) deferredBindingsByKey else bindingsByKey
    val rules =
      SuspendBindingRules<String, StringTypeKey, StringContextualTypeKey, StringBinding>(
        findBinding = indexedBindings::get,
        bindingCanPassThrough = { _, _ -> false },
      )
    return SuspendBindingWorklist(
      findBinding = indexedBindings::get,
      bindingIsSuspend = { binding ->
        hasSuspendSources && binding.typeKey in suspendSourceKeys
      },
      skipDependencyTraversal = { false },
      rules = rules,
    )
  }
}

/**
 * Measures mixed application-shaped graphs without multiplying the simple topology benchmarks.
 *
 * Binding kinds, scopes, and qualifiers describe the test workload. Scope resolution itself lives
 * in the compiler and IDE adapters, so it is not measured by these shared graph benchmarks.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
open class RepresentativeGraphBenchmark {

  @Param("100", "1000", "10000") var size: Int = 0

  private lateinit var workload: GraphWorkload
  private lateinit var bindingsByKey: Map<StringTypeKey, StringBinding>
  private lateinit var suspendSourceKeys: Set<StringTypeKey>

  @Setup(Level.Trial)
  fun setUp() {
    workload = GraphWorkload.generate(GraphWorkloadSpec(size = size))
    bindingsByKey = workload.nodes.associate { node -> node.binding.typeKey to node.binding }
    suspendSourceKeys = buildSet {
      for (node in workload.nodes) {
        if (node.index % 64 == 0 && node.binding.typeKey in workload.reachableKeys) {
          add(node.binding.typeKey)
        }
      }
    }
  }

  @Benchmark
  fun sealRepresentativeGraph(blackhole: Blackhole) {
    val graph = fullyPopulatedGraph()
    val result =
      with(TraceScope.noop()) {
        graph.seal(roots = workload.roots, shrinkUnusedBindings = false)
      }
    blackhole.consume(result)
  }

  @Benchmark
  fun sealRepresentativeGraphWithPruning(blackhole: Blackhole) {
    val graph = fullyPopulatedGraph()
    val result =
      with(TraceScope.noop()) {
        graph.seal(roots = workload.roots, shrinkUnusedBindings = true)
      }
    blackhole.consume(result)
  }

  @Benchmark
  fun sealRepresentativeGraphWithLazyBindings(blackhole: Blackhole) {
    val graph = workload.newGraph()
    val result =
      with(TraceScope.noop()) {
        graph.seal(roots = workload.roots, shrinkUnusedBindings = true)
      }
    blackhole.consume(result)
  }

  @Benchmark
  fun propagateSuspendInRepresentativeGraph(blackhole: Blackhole) {
    val rules =
      SuspendBindingRules<String, StringTypeKey, StringContextualTypeKey, StringBinding>(
        findBinding = bindingsByKey::get,
        bindingCanPassThrough = { _, _ -> false },
      )
    val worklist =
      SuspendBindingWorklist(
        findBinding = bindingsByKey::get,
        bindingIsSuspend = { binding -> binding.typeKey in suspendSourceKeys },
        skipDependencyTraversal = { false },
        rules = rules,
      )
    blackhole.consume(worklist.analyze(workload.reachableKeys))
  }

  private fun fullyPopulatedGraph(): StringGraph {
    val graph = workload.newGraph()
    val stack = StringBindingStack("RepresentativeGraph")
    for (binding in workload.lazyBindingsByKey.values) {
      graph.tryPut(binding, stack)
    }
    return graph
  }
}
