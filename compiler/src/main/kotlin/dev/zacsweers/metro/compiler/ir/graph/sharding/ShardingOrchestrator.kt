// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.Component
import dev.zacsweers.metro.compiler.graph.GraphTopology
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import java.util.*

/**
 * Partitions bindings into shards when a graph exceeds [MetroOptions.keysPerGraphShard].
 *
 * Keeps SCCs together to preserve circular dependencies (including Provider<T> cycles) and respects
 * topological ordering.
 *
 * Returns null if sharding isn't needed:
 * - Graph sharding is disabled
 * - There are too few bindings
 */
internal class ShardingOrchestrator(private val options: MetroOptions) {

  /**
   * Returns shard groups in topologically sorted order, or null if sharding isn't needed. Each list
   * contains type keys for the bindings in that shard.
   */
  fun computeShardGroups(topologyData: GraphTopology<IrTypeKey>?): List<List<IrTypeKey>>? {
    if (topologyData == null) return null

    val maxPerShard = options.keysPerGraphShard
    val adjacencyKeys = topologyData.adjacency.keys

    if (
      adjacencyKeys.isEmpty() || !options.enableGraphSharding || adjacencyKeys.size <= maxPerShard
    )
      return null

    val keyShards =
      partitionUsingSCCs(
        adjacency = topologyData.adjacency,
        sortedKeys = topologyData.sortedKeys,
        components = topologyData.components,
        componentOf = topologyData.componentOf,
        maxBindingsPerShard = maxPerShard,
      )

    return keyShards
  }

  /**
   * Partitions bindings by SCCs while respecting [maxBindingsPerShard].
   *
   * Processes SCCs in topological order, keeping all bindings in an SCC together so circular
   * dependencies (including Provider<T> cycles) stay in the same shard.
   *
   * Isolated keys are bindings not part of any cycle (single-node SCCs not tracked in
   * [componentOf]). They're processed after multi-node components, relying on their relative order
   * in [sortedKeys].
   *
   * If a single SCC exceeds [maxBindingsPerShard], it's kept whole to preserve cycle integrity.
   */
  private fun partitionUsingSCCs(
    adjacency: Map<IrTypeKey, SortedSet<IrTypeKey>>,
    sortedKeys: List<IrTypeKey>,
    components: List<Component<IrTypeKey>>,
    componentOf: Map<IrTypeKey, Int>,
    maxBindingsPerShard: Int,
  ) = buildList {
    var currentShard = mutableListOf<IrTypeKey>()

    val orderedComponents = sortedKeys.mapNotNull { componentOf[it] }.distinct()
    val isolatedKeys = sortedKeys.filter { componentOf[it] == null && adjacency.containsKey(it) }

    for (componentId in orderedComponents) {
      val component = components[componentId]
      val validKeys = component.vertices.filter(adjacency::containsKey)
      if (validKeys.isEmpty()) continue

      if (currentShard.isNotEmpty() && currentShard.size + validKeys.size > maxBindingsPerShard) {
        add(currentShard)
        currentShard = mutableListOf()
      }

      currentShard += validKeys
    }

    // Append isolated keys last
    for (key in isolatedKeys) {
      if (currentShard.isNotEmpty() && currentShard.size + 1 > maxBindingsPerShard) {
        add(currentShard)
        currentShard = mutableListOf()
      }
      currentShard += key
    }

    if (currentShard.isNotEmpty()) add(currentShard)
  }
}
