// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import java.util.*
import kotlin.test.Test

class GraphPartitionerTest {

  @Test
  fun `basic partitioning with single-node components`() {
    // Simple chain: a -> b -> c -> d -> e (5 keys, each in its own single-node component)
    val topology =
      buildTopology(
        sortedKeys = listOf("e", "d", "c", "b", "a"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("c"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("e"),
            "e" to sortedSetOf(),
          ),
        components =
          listOf(
            Component(0, mutableListOf("e")),
            Component(1, mutableListOf("d")),
            Component(2, mutableListOf("c")),
            Component(3, mutableListOf("b")),
            Component(4, mutableListOf("a")),
          ),
        componentOf = mapOf("e" to 0, "d" to 1, "c" to 2, "b" to 3, "a" to 4),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // Should split into 3 partitions: [e, d], [c, b], [a]
    assertThat(partitions).hasSize(3)
    assertThat(partitions.flatten()).containsExactly("e", "d", "c", "b", "a").inOrder()
  }

  @Test
  fun `cycle kept together in single partition`() {
    // Cycle: a -> b -> c -> a (all in one SCC)
    val topology =
      buildTopology(
        sortedKeys = listOf("a", "b", "c"),
        adjacency =
          sortedMapOf("a" to sortedSetOf("b"), "b" to sortedSetOf("c"), "c" to sortedSetOf("a")),
        components = listOf(Component(0, mutableListOf("a", "b", "c"))),
        componentOf = mapOf("a" to 0, "b" to 0, "c" to 0),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // Even though maxPerPartition=2, all 3 keys should stay together due to cycle
    assertThat(partitions).hasSize(1)
    assertThat(partitions[0]).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun `cycle in middle of chain - A-B-(C-D)-E pattern`() {
    // Pattern: a -> b -> (c <-> d) -> e where c-d form a cycle
    // sortedKeys should have the correct order with c and d together
    val topology =
      buildTopology(
        sortedKeys = listOf("e", "c", "d", "b", "a"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("c"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("c", "e"),
            "e" to sortedSetOf(),
          ),
        components =
          listOf(
            Component(0, mutableListOf("c", "d")), // multi-node cycle
            Component(1, mutableListOf("e")),
            Component(2, mutableListOf("b")),
            Component(3, mutableListOf("a")),
          ),
        componentOf = mapOf("c" to 0, "d" to 0, "e" to 1, "b" to 2, "a" to 3),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // c and d must stay together due to cycle
    assertThat(partitions.any { it.containsAll(listOf("c", "d")) }).isTrue()

    // All keys should be present in topo order
    assertThat(partitions.flatten()).containsExactly("e", "c", "d", "b", "a").inOrder()
  }

  @Test
  fun `multiple independent cycles`() {
    // Two independent cycles: (a <-> b) and (c <-> d)
    val topology =
      buildTopology(
        sortedKeys = listOf("a", "b", "c", "d"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("a"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("c"),
          ),
        components =
          listOf(Component(0, mutableListOf("a", "b")), Component(1, mutableListOf("c", "d"))),
        componentOf = mapOf("a" to 0, "b" to 0, "c" to 1, "d" to 1),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // Each cycle should be in its own partition
    assertThat(partitions).hasSize(2)
    assertThat(partitions[0]).containsExactly("a", "b")
    assertThat(partitions[1]).containsExactly("c", "d")
  }

  @Test
  fun `cycle with isolated keys maintains topo order`() {
    // One cycle (a, b) and two isolated keys (c, d) in single-node components
    // sortedKeys determines the overall order
    val topology =
      buildTopology(
        sortedKeys = listOf("c", "d", "a", "b"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("a"),
            "c" to sortedSetOf(),
            "d" to sortedSetOf(),
          ),
        components =
          listOf(
            Component(0, mutableListOf("a", "b")), // multi-node cycle
            Component(1, mutableListOf("c")),
            Component(2, mutableListOf("d")),
          ),
        componentOf = mapOf("a" to 0, "b" to 0, "c" to 1, "d" to 2),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // c and d first (single-node), then a and b together (cycle)
    assertThat(partitions).hasSize(2)
    assertThat(partitions[0]).containsExactly("c", "d")
    assertThat(partitions[1]).containsExactly("a", "b")
  }

  @Test
  fun `all keys fit in single partition`() {
    val topology =
      buildTopology(
        sortedKeys = listOf("a", "b", "c"),
        adjacency =
          sortedMapOf("a" to sortedSetOf("b"), "b" to sortedSetOf("c"), "c" to sortedSetOf()),
        components =
          listOf(
            Component(0, mutableListOf("a")),
            Component(1, mutableListOf("b")),
            Component(2, mutableListOf("c")),
          ),
        componentOf = mapOf("a" to 0, "b" to 1, "c" to 2),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 10)

    assertThat(partitions).hasSize(1)
    assertThat(partitions[0]).containsExactly("a", "b", "c").inOrder()
  }

  @Test
  fun `oversized cycle exceeds max but stays together`() {
    // 5-node cycle with maxPerPartition=2
    val topology =
      buildTopology(
        sortedKeys = listOf("a", "b", "c", "d", "e"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("c"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("e"),
            "e" to sortedSetOf("a"),
          ),
        components = listOf(Component(0, mutableListOf("a", "b", "c", "d", "e"))),
        componentOf = mapOf("a" to 0, "b" to 0, "c" to 0, "d" to 0, "e" to 0),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // Entire cycle must stay together despite exceeding limit
    assertThat(partitions).hasSize(1)
    assertThat(partitions[0]).containsExactly("a", "b", "c", "d", "e").inOrder()
  }

  @Test
  fun `empty topology returns empty list`() {
    val topology =
      buildTopology(
        sortedKeys = emptyList(),
        adjacency = sortedMapOf(),
        components = emptyList(),
        componentOf = emptyMap(),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 10)

    assertThat(partitions).isEmpty()
  }

  @Test
  fun `keys not in adjacency are filtered out`() {
    // sortedKeys contains "x" but adjacency doesn't
    val topology =
      buildTopology(
        sortedKeys = listOf("a", "b", "x"),
        adjacency = sortedMapOf("a" to sortedSetOf("b"), "b" to sortedSetOf()),
        components = listOf(Component(0, mutableListOf("a")), Component(1, mutableListOf("b"))),
        componentOf = mapOf("a" to 0, "b" to 1, "x" to 2),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 10)

    assertThat(partitions.flatten()).containsExactly("a", "b")
    assertThat(partitions.flatten()).doesNotContain("x")
  }

  @Test
  fun `keys preserve sortedKeys order within partitions`() {
    // Verify that the output preserves the topological order from sortedKeys
    val topology =
      buildTopology(
        sortedKeys = listOf("x", "y", "z"),
        adjacency = sortedMapOf("x" to sortedSetOf(), "y" to sortedSetOf(), "z" to sortedSetOf()),
        components =
          listOf(
            Component(0, mutableListOf("x")),
            Component(1, mutableListOf("y")),
            Component(2, mutableListOf("z")),
          ),
        componentOf = mapOf("x" to 0, "y" to 1, "z" to 2),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // Should maintain x, y, z order
    assertThat(partitions.flatten()).containsExactly("x", "y", "z").inOrder()
  }

  private fun buildTopology(
    sortedKeys: List<String>,
    adjacency: SortedMap<String, SortedSet<String>>,
    components: List<Component<String>>,
    componentOf: Map<String, Int>,
  ): GraphTopology<String> =
    GraphTopology(
      sortedKeys = sortedKeys,
      deferredTypes = emptySet(),
      reachableKeys = adjacency.keys.toSet(),
      adjacency = adjacency,
      components = components,
      componentOf = componentOf,
      componentDag = emptyMap(),
    )
}
