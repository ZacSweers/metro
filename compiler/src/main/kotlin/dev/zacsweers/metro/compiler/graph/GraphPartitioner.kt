// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/**
 * Divides a dependency graph into balanced groups while keeping strongly connected components
 * together.
 *
 * Splits large graphs into smaller parts to avoid JVM method size limits, ensuring circular
 * dependencies (SCCs) remain in the same group to prevent runtime issues.
 *
 * @param keysPerGraphShard Maximum keys per shard, though a single cycle may exceed this limit.
 * @return Groups in dependency order.
 */
internal fun <T> GraphTopology<T>.partitionBySCCs(keysPerGraphShard: Int): List<List<T>> {
  // 1. Identify valid keys and pre-group multi-node components.
  // We do this upfront to avoid repeated expensive filtering inside the main loop.
  val validKeys = sortedKeys.filter { it in adjacency }
  val multiNodeGroups = mutableMapOf<Int, MutableList<T>>()

  // Identify which component IDs represent cycles (size > 1)
  val multiNodeIds = components.filter { it.vertices.size > 1 }.mapTo(HashSet()) { it.id }

  // Populate the groups preserving topological order
  for (key in validKeys) {
    val id = componentOf[key]
    if (id != null && id in multiNodeIds) {
      multiNodeGroups.getOrPut(id) { mutableListOf() }.add(key)
    }
  }

  // 2. Build the partitions
  return buildList {
    var currentBatch = mutableListOf<T>()
    val processedComponents = mutableSetOf<Int>()

    for (key in validKeys) {
      val componentId = componentOf[key]
      val isMultiNode = componentId != null && componentId in multiNodeIds

      // If this is a cycle we haven't processed yet, handle the whole group at once
      if (isMultiNode) {
        if (componentId in processedComponents) continue
        processedComponents += componentId

        val group = multiNodeGroups[componentId]!!

        // If adding this group exceeds the limit, flush the current batch first
        if (currentBatch.isNotEmpty() && (currentBatch.size + group.size > keysPerGraphShard)) {
          add(currentBatch)
          currentBatch = mutableListOf()
        }
        currentBatch += group
      } else {
        // Handle standard single nodes
        if (currentBatch.isNotEmpty() && (currentBatch.size + 1 > keysPerGraphShard)) {
          add(currentBatch)
          currentBatch = mutableListOf()
        }
        currentBatch += key
      }
    }

    if (currentBatch.isNotEmpty()) {
      add(currentBatch)
    }
  }
}
