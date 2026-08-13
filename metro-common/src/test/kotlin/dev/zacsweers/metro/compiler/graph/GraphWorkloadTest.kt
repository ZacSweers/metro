// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.junit.Test

class GraphWorkloadTest : TraceScope by TraceScope.noop() {

  @Test
  fun `same seed reproduces every node edge and root`() {
    val first = GraphWorkload.generate(GraphWorkloadSpec(size = 100))
    val second = GraphWorkload.generate(GraphWorkloadSpec(size = 100))

    assertThat(second.nodes).containsExactlyElementsIn(first.nodes).inOrder()
    assertThat(second.roots.keys).containsExactlyElementsIn(first.roots.keys).inOrder()
    assertThat(second.expectedDeferredKeys).containsExactlyElementsIn(first.expectedDeferredKeys)

    val differentSeed = GraphWorkload.generate(GraphWorkloadSpec(size = 100, seed = 42))
    assertThat(differentSeed.nodes).isNotEqualTo(first.nodes)
  }

  @Test
  fun `workload contains realistic binding metadata and graph shapes`() {
    val workload = GraphWorkload.generate(GraphWorkloadSpec(size = 1_000))
    val reachable = workload.nodes.filter { it.binding.typeKey in workload.reachableKeys }

    assertThat(reachable.map { it.kind }).containsAtLeastElementsIn(WorkloadBindingKind.entries)
    assertThat(reachable.map { it.scope }.toSet().size).isAtLeast(6)
    assertThat(reachable.count { it.binding.typeKey.qualifier != null }).isAtLeast(100)
    assertThat(workload.roots.size).isAtLeast(16)
    assertThat(workload.lazyBindingsByKey).isNotEmpty()
    assertThat(workload.unreachableKeys).hasSize(125)

    val aggregate = reachable.first {
      it.kind == WorkloadBindingKind.SET_MULTIBINDING ||
        it.kind == WorkloadBindingKind.MAP_MULTIBINDING
    }
    assertThat(aggregate.binding.dependencies.size).isAtLeast(16)

    val hub = reachable.first().binding.typeKey
    val incoming = reachable.count { node -> node.binding.dependencies.any { it.typeKey == hub } }
    assertThat(incoming).isAtLeast(100)
    assertThat(workload.expectedDeferredKeys).isNotEmpty()
  }

  @Test
  fun `wrapped dependency keeps its target qualifier`() {
    val target = StringTypeKey("Target", "@Named(network)")
    val dependency = StringContextualTypeKey.create(StringTypeKey("() -> Target", target.qualifier))

    assertThat(dependency.typeKey).isEqualTo(target)
    assertThat(dependency.isDeferrable).isTrue()
  }

  @Test
  fun `seal discovers constructor bindings and accepts deferred cycles`() {
    val workload = GraphWorkload.generate(GraphWorkloadSpec(size = 100))
    val discovered = linkedSetOf<StringTypeKey>()
    val graph = workload.newGraph { discovered += it }

    assertThat(graph.bindings.size).isEqualTo(workload.eagerBindings.size)
    for (lazyKey in workload.lazyBindingsByKey.keys) {
      assertThat(lazyKey in graph).isFalse()
    }

    val topology = graph.seal(roots = workload.roots, shrinkUnusedBindings = true)

    assertThat(discovered).containsExactlyElementsIn(workload.lazyBindingsByKey.keys)
    assertThat(topology.reachableKeys).containsExactlyElementsIn(workload.reachableKeys)
    assertThat(topology.deferredTypes).containsAtLeastElementsIn(workload.expectedDeferredKeys)
    assertThat(topology.reachableKeys).containsNoneIn(workload.unreachableKeys)
  }

  @Test
  fun `unreachable bindings are retained only when shrinking is disabled`() {
    val workload = GraphWorkload.generate(GraphWorkloadSpec(size = 100))
    val pruned = workload.newGraph().seal(roots = workload.roots, shrinkUnusedBindings = true)
    val complete = workload.newGraph().seal(roots = workload.roots, shrinkUnusedBindings = false)

    assertThat(pruned.sortedKeys).containsNoneIn(workload.unreachableKeys)
    assertThat(complete.sortedKeys).containsAtLeastElementsIn(workload.unreachableKeys)
    assertThat(complete.sortedKeys).hasSize(workload.spec.size)
  }
}
