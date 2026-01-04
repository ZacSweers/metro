// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ObjectIntMap
import androidx.collection.ObjectList
import androidx.collection.emptyIntObjectMap
import androidx.collection.emptyObjectIntMap
import androidx.collection.emptyObjectList
import androidx.collection.emptyOrderedScatterSet
import androidx.collection.mutableObjectListOf
import androidx.collection.objectIntMapOf
import androidx.collection.objectListOf
import com.google.common.truth.Truth.assertThat
import java.util.*
import kotlin.test.Test

class GraphPartitionerTest {

  @Test
  fun `basic partitioning with single-node components`() {
    // Simple chain: a -> b -> c -> d -> e (5 keys, each in its own single-node component)
    val topology =
      buildTopology(
        sortedKeys = objectListOf("e", "d", "c", "b", "a"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("c"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("e"),
            "e" to sortedSetOf(),
          ),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("e")),
            Component(1, mutableObjectListOf("d")),
            Component(2, mutableObjectListOf("c")),
            Component(3, mutableObjectListOf("b")),
            Component(4, mutableObjectListOf("a")),
          ),
        componentOf = objectIntMapOf("e", 0, "d", 1, "c", 2, "b", 3, "a", 4),
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
        sortedKeys = objectListOf("a", "b", "c"),
        adjacency =
          sortedMapOf("a" to sortedSetOf("b"), "b" to sortedSetOf("c"), "c" to sortedSetOf("a")),
        components = objectListOf(Component(0, mutableObjectListOf("a", "b", "c"))),
        componentOf = objectIntMapOf("a", 0, "b", 0, "c", 0),
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
        sortedKeys = objectListOf("e", "c", "d", "b", "a"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("c"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("c", "e"),
            "e" to sortedSetOf(),
          ),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("c", "d")), // multi-node cycle
            Component(1, mutableObjectListOf("e")),
            Component(2, mutableObjectListOf("b")),
            Component(3, mutableObjectListOf("a")),
          ),
        componentOf = objectIntMapOf("c", 0, "d", 0, "e", 1, "b", 2, "a", 3),
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
        sortedKeys = objectListOf("a", "b", "c", "d"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("a"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("c"),
          ),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("a", "b")),
            Component(1, mutableObjectListOf("c", "d")),
          ),
        componentOf = objectIntMapOf("a", 0, "b", 0, "c", 1, "d", 1),
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
        sortedKeys = objectListOf("c", "d", "a", "b"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("a"),
            "c" to sortedSetOf(),
            "d" to sortedSetOf(),
          ),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("a", "b")), // multi-node cycle
            Component(1, mutableObjectListOf("c")),
            Component(2, mutableObjectListOf("d")),
          ),
        componentOf = objectIntMapOf("a", 0, "b", 0, "c", 1, "d", 2),
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
        sortedKeys = objectListOf("a", "b", "c"),
        adjacency =
          sortedMapOf("a" to sortedSetOf("b"), "b" to sortedSetOf("c"), "c" to sortedSetOf()),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("a")),
            Component(1, mutableObjectListOf("b")),
            Component(2, mutableObjectListOf("c")),
          ),
        componentOf = objectIntMapOf("a", 0, "b", 1, "c", 2),
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
        sortedKeys = objectListOf("a", "b", "c", "d", "e"),
        adjacency =
          sortedMapOf(
            "a" to sortedSetOf("b"),
            "b" to sortedSetOf("c"),
            "c" to sortedSetOf("d"),
            "d" to sortedSetOf("e"),
            "e" to sortedSetOf("a"),
          ),
        components = objectListOf(Component(0, mutableObjectListOf("a", "b", "c", "d", "e"))),
        componentOf = objectIntMapOf("a", 0, "b", 0, "c", 0, "d", 0, "e", 0),
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
        sortedKeys = emptyObjectList(),
        adjacency = sortedMapOf(),
        components = emptyObjectList(),
        componentOf = emptyObjectIntMap(),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 10)

    assertThat(partitions).isEmpty()
  }

  @Test
  fun `keys not in adjacency are filtered out`() {
    // sortedKeys contains "x" but adjacency doesn't
    val topology =
      buildTopology(
        sortedKeys = objectListOf("a", "b", "x"),
        adjacency = sortedMapOf("a" to sortedSetOf("b"), "b" to sortedSetOf()),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("a")),
            Component(1, mutableObjectListOf("b")),
          ),
        componentOf = objectIntMapOf("a", 0, "b", 1, "x", 2),
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
        sortedKeys = objectListOf("x", "y", "z"),
        adjacency = sortedMapOf("x" to sortedSetOf(), "y" to sortedSetOf(), "z" to sortedSetOf()),
        components =
          objectListOf(
            Component(0, mutableObjectListOf("x")),
            Component(1, mutableObjectListOf("y")),
            Component(2, mutableObjectListOf("z")),
          ),
        componentOf = objectIntMapOf("x", 0, "y", 1, "z", 2),
      )

    val partitions = topology.partitionBySCCs(keysPerGraphShard = 2)

    // Should maintain x, y, z order
    assertThat(partitions.flatten()).containsExactly("x", "y", "z").inOrder()
  }

  private fun buildTopology(
    sortedKeys: ObjectList<String>,
    adjacency: SortedMap<String, SortedSet<String>>,
    components: ObjectList<Component<String>>,
    componentOf: ObjectIntMap<String>,
  ): GraphTopology<String> =
    GraphTopology(
      sortedKeys = sortedKeys,
      deferredTypes = emptyOrderedScatterSet(),
      reachableKeys = adjacency.keys.toSet(),
      adjacency = adjacency,
      components = components,
      componentOf = componentOf,
      componentDag = emptyIntObjectMap(),
    )
}
