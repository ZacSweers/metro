// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.tracing.TraceScope
import java.util.SortedSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CycleGraphWorkloadTest : TraceScope by TraceScope.noop() {
  @Test
  fun `every scenario uses the requested vertex count and stable candidate order`() {
    for (scenario in CycleGraphScenario.entries) {
      for (size in listOf(3, 4, 7, 12)) {
        val workload = CycleGraphWorkload.generate(scenario, size)
        val keys = workload.bindings.map { it.typeKey }

        assertEquals(scenario, workload.scenario)
        assertEquals(size, workload.size)
        assertEquals(size, keys.toSet().size)
        assertEquals(keys.sorted(), keys)
        assertEquals(setOf(keys.first()), workload.roots.keys.map { it.typeKey }.toSet())
        assertTrue(workload.bindings.none { it.isImplicitlyDeferrable })
        assertTrue(workload.bindings.flatMap { it.dependencies }.all { it.typeKey in keys })
        assertEquals(size, workload.newGraph().bindings.size)

        val repeated = CycleGraphWorkload.generate(scenario, size)
        assertEquals(workload.bindings, repeated.bindings)
        assertEquals(workload.roots.keys, repeated.roots.keys)
      }
    }
  }

  @Test
  fun `cycle families retain their sparse and dense edge counts`() {
    val size = 12
    val tailSize = size - 2
    val expectedEdges =
      mapOf(
        CycleGraphScenario.PROVIDER_RING to size,
        CycleGraphScenario.EAGER_RING_WITH_DEFERRED_CHORDS to size * 2,
        CycleGraphScenario.SHARED_HUB to size + 1,
        CycleGraphScenario.COMPLETE_DEFERRED to size * (size - 1),
        CycleGraphScenario.SPARSE_HARD_TAIL to size + 1,
        CycleGraphScenario.DENSE_HARD_TAIL to 3 + tailSize * (tailSize + 1) / 2,
      )
    val expectedDeferredEdges =
      mapOf(
        CycleGraphScenario.PROVIDER_RING to 1,
        CycleGraphScenario.EAGER_RING_WITH_DEFERRED_CHORDS to size,
        CycleGraphScenario.SHARED_HUB to size + 1,
        CycleGraphScenario.COMPLETE_DEFERRED to size * (size - 1),
        CycleGraphScenario.SPARSE_HARD_TAIL to 1,
        CycleGraphScenario.DENSE_HARD_TAIL to 1,
      )

    for (scenario in CycleGraphScenario.entries) {
      val workload = CycleGraphWorkload.generate(scenario, size)
      val dependencies = workload.bindings.flatMap { it.dependencies }
      assertEquals(expectedEdges.getValue(scenario), dependencies.size)
      assertEquals(expectedDeferredEdges.getValue(scenario), dependencies.count { it.isDeferrable })

      val adjacency = sortedMapOf<StringTypeKey, SortedSet<StringTypeKey>>()
      for (binding in workload.bindings) {
        adjacency[binding.typeKey] = binding.dependencies.mapTo(sortedSetOf()) { it.typeKey }
      }
      val components = adjacency.computeStronglyConnectedComponents().components
      assertEquals(1, components.size)
      assertEquals(size, components.single().vertices.size)
    }
  }

  @Test
  fun `valid cycles choose the expected inclusion minimal deferrals`() {
    val scenarios =
      listOf(
        CycleGraphScenario.PROVIDER_RING,
        CycleGraphScenario.SHARED_HUB,
        CycleGraphScenario.COMPLETE_DEFERRED,
      )
    for (scenario in scenarios) {
      for (size in listOf(3, 4, 7)) {
        val workload = CycleGraphWorkload.generate(scenario, size)
        assertFalse(workload.hasHardCycle)
        val prepared = workload.newGraph().prepareSeal(roots = workload.roots)
        val analysis = prepared.analyze()
        val repeated = prepared.analyze()
        assertNull(analysis.hardCycle)
        assertEquals(analysis.topology?.sortedKeys, repeated.topology?.sortedKeys)
        assertEquals(analysis.sortedCycles, repeated.sortedCycles)
        val topology = prepared.finish(analysis)
        assertEquals(topology, prepared.finish(analysis))
        assertEquals(workload.expectedDeferredKeys, topology.deferredTypes)
        assertEquals(workload.expectedDeferredKeys, repeated.topology?.deferredTypes)
        assertEquals(size, topology.reachableKeys.size)

        val vertices = workload.bindings.map { it.typeKey }
        val adjacency =
          workload.bindings.associate { binding ->
            binding.typeKey to binding.dependencies.mapTo(linkedSetOf()) { it.typeKey }
          }
        val deferredEdges =
          workload.bindings.associate { binding ->
            binding.typeKey to
              binding.dependencies
                .filter { it.isDeferrable }
                .mapTo(linkedSetOf()) {
                  it.typeKey
                }
          }
        val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)
        assertTrue(checker.isAcyclicWith(topology.deferredTypes))
        // Every selected source must be necessary to keep the remaining edges acyclic.
        for (candidate in topology.deferredTypes) {
          assertFalse(checker.isAcyclicWith(topology.deferredTypes - candidate))
        }
      }
    }
  }

  @Test
  fun `invalid cycles produce repeatable witnesses containing only eager edges`() {
    val scenarios =
      listOf(
        CycleGraphScenario.EAGER_RING_WITH_DEFERRED_CHORDS,
        CycleGraphScenario.SPARSE_HARD_TAIL,
        CycleGraphScenario.DENSE_HARD_TAIL,
      )
    for (scenario in scenarios) {
      for (size in listOf(3, 4, 7)) {
        val workload = CycleGraphWorkload.generate(scenario, size)
        assertTrue(workload.hasHardCycle)
        assertNull(workload.expectedDeferredKeys)
        val prepared = workload.newGraph().prepareSeal(roots = workload.roots)
        val analysis = prepared.analyze()
        assertNull(analysis.topology)
        val cycle = assertNotNull(analysis.hardCycle)
        assertEquals(cycle, prepared.analyze().hardCycle)
        assertEquals(cycle.size, cycle.toSet().size)
        val expectedCycleSize =
          if (scenario == CycleGraphScenario.EAGER_RING_WITH_DEFERRED_CHORDS) {
            size
          } else {
            2
          }
        assertEquals(expectedCycleSize, cycle.size)
        val bindingsByKey = workload.bindings.associateBy { it.typeKey }
        for (index in cycle.indices) {
          val source = bindingsByKey.getValue(cycle[index])
          val target = cycle[(index + 1) % cycle.size]
          assertTrue(source.dependencies.any { it.typeKey == target && !it.isDeferrable })
        }
        val failure = assertFailsWith<IllegalStateException> { prepared.finish(analysis) }
        assertTrue(failure.message.orEmpty().contains("[Metro/DependencyCycle]"))
        val repeatedFailure = assertFailsWith<IllegalStateException> { prepared.finish(analysis) }
        assertEquals(failure.message, repeatedFailure.message)
      }
    }
  }

  @Test
  fun `undersized workloads are rejected`() {
    for (scenario in CycleGraphScenario.entries) {
      assertFailsWith<IllegalArgumentException> { CycleGraphWorkload.generate(scenario, 2) }
    }
  }
}
