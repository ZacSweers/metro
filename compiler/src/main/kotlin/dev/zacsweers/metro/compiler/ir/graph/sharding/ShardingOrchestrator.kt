// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.graph.Component
import dev.zacsweers.metro.compiler.graph.GraphTopology
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.isInvisibleGeneratedGraph

/**
 * Partitions bindings into shards when a graph exceeds [MetroOptions.keysPerGraphShard].
 *
 * Keeps SCCs together to preserve circular dependencies (including Provider<T> cycles), respects
 * topological ordering, and merges small tail shards.
 *
 * Returns null if sharding isn't needed:
 * - Graph sharding is disabled
 * - There are too few bindings
 * - It's either a dynamic graph or a graph extension
 */
internal class ShardingOrchestrator(
  private val node: DependencyGraphNode,
  private val options: MetroOptions,
) {

  /**
   * Returns shard groups in topologically sorted order, or null if sharding isn't needed. Each list
   * contains type keys for the bindings in that shard.
   */
  fun computeShardGroups(topologyData: GraphTopology<IrTypeKey>?): List<List<IrTypeKey>>? {
    if (topologyData == null) return null

    val maxPerShard = options.keysPerGraphShard
    val adjacencyKeys = topologyData.adjacency.keys

    if (
      adjacencyKeys.isEmpty() ||
        !options.enableGraphSharding ||
        adjacencyKeys.size <= maxPerShard ||
        node.sourceGraph.origin.isInvisibleGeneratedGraph
    )
      return null

    val keyShards =
      partitionUsingSCCs(
        containsKey = topologyData.adjacency::containsKey,
        sortedKeys = topologyData.sortedKeys,
        components = topologyData.components,
        componentOf = topologyData.componentOf,
        maxBindingsPerShard = maxPerShard,
      )

    return keyShards.ifEmpty { null }
  }

  /**
   * Partitions bindings by SCCs while respecting [maxBindingsPerShard].
   *
   * Processes SCCs in topological order, keeping all bindings in an SCC together so circular
   * dependencies (including Provider<T> cycles) stay in the same shard. Adds isolated keys at the
   * end and merges small tail shards.
   */
  private inline fun partitionUsingSCCs(
    containsKey: (IrTypeKey) -> Boolean,
    sortedKeys: List<IrTypeKey>,
    components: List<Component<IrTypeKey>>,
    componentOf: Map<IrTypeKey, Int>,
    maxBindingsPerShard: Int,
  ) = buildList {
    var currentShard = mutableListOf<IrTypeKey>()

    // Build ordered component ids from sortedKeys and collect isolated keys
    val orderedComponents = sortedKeys.mapNotNull { componentOf[it] }.distinct()
    val isolatedKeys = sortedKeys.filter { componentOf[it] == null && containsKey(it) }

    // Append components to shards, respecting size limits
    for (cid in orderedComponents) {
      val component = components[cid]
      val validKeys = component.vertices.filter(containsKey)
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

    // Merge small tail shards into the previous if they fit together
    if (size >= 2) {
      val last = this[lastIndex]
      val prev = this[lastIndex - 1]

      if (prev.size + last.size <= maxBindingsPerShard) {
        prev += last
        removeLast()
      }
    }
  }
}
