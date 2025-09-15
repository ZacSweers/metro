// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.sharding

import dev.zacsweers.metro.compiler.graph.Component
import dev.zacsweers.metro.compiler.graph.TarjanResult
import dev.zacsweers.metro.compiler.ir.IrBinding
import dev.zacsweers.metro.compiler.ir.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Analyzes a dependency graph and creates a sharding plan following Dagger's approach.
 *
 * This implementation follows Dagger's ComponentImplementation.bindingPartitions() exactly:
 * 1. Iterates through SCCs in topological order
 * 2. Accumulates bindings until threshold is reached
 * 3. Keeps cycles (SCCs) together in the same shard
 * 4. Ensures Shard{i} doesn't depend on Shard{i+j}
 */
internal class ShardAnalyzer(
  private val keysPerShard: Int = DEFAULT_KEYS_PER_SHARD
) {
  
  companion object {
    private const val DEFAULT_KEYS_PER_SHARD = 100
  }
  /**
   * Analyzes the binding graph and creates a sharding plan.
   *
   * @param bindingGraph The binding graph to analyze
   * @param sccs The strongly connected components from Tarjan's algorithm
   * @return A sharding plan, or null if sharding is not needed
   */
  internal fun analyze(
    bindingGraph: IrBindingGraph,
    sccs: TarjanResult<IrTypeKey>
  ): ShardingPlan? {
    val localBindings = bindingGraph.bindingsSnapshot().filterValues { binding ->
      // Only shard local bindings (not inherited from parent graphs)
      binding !is IrBinding.Absent && shouldIncludeInSharding(binding)
    }

    // Check if sharding is needed
    val maxPartitions = (localBindings.size / keysPerShard) + 1
    if (maxPartitions <= 1) {
      // No sharding needed - everything fits in component shard
      return null
    }

    // Partition bindings following Dagger's algorithm
    val partitions = partitionBindings(localBindings, sccs)
    
    // Build the sharding plan
    return buildShardingPlan(partitions, bindingGraph)
  }

  /**
   * Partitions bindings into shards following Dagger's exact algorithm.
   * 
   * From Dagger's bindingPartitions():
   * "Iterate through all SCCs in order until all bindings local to this component are partitioned."
   */
  private fun partitionBindings(
    localBindings: Map<IrTypeKey, IrBinding>,
    sccs: TarjanResult<IrTypeKey>
  ): List<List<IrTypeKey>> {
    val partitions = mutableListOf<List<IrTypeKey>>()
    var currentPartition = mutableListOf<IrTypeKey>()
    
    // Iterate through SCCs in topological order
    for (component in sccs.components) {
      // Add all bindings from this SCC to current partition
      for (vertex in component.vertices) {
        if (vertex in localBindings) {
          currentPartition.add(vertex)
        }
      }
      
      // Check if we've reached the threshold
      if (currentPartition.size >= keysPerShard) {
        partitions.add(currentPartition.toList())
        currentPartition = mutableListOf()
      }
    }
    
    // Add remaining bindings if any
    if (currentPartition.isNotEmpty()) {
      partitions.add(currentPartition.toList())
    }
    
    return partitions
  }

  /**
   * Builds the final sharding plan from the partitions.
   */
  private fun buildShardingPlan(
    partitions: List<List<IrTypeKey>>,
    bindingGraph: IrBindingGraph
  ): ShardingPlan {
    val shards = mutableListOf<ShardingPlan.Shard>()
    val bindingToShard = mutableMapOf<IrTypeKey, Int>()
    
    partitions.forEachIndexed { index, partition ->
      // Calculate dependencies for this shard
      val dependencies = calculateShardDependencies(partition, bindingGraph, bindingToShard)
      
      // Create the shard
      val shard = ShardingPlan.Shard(
        index = index,
        bindings = partition.toSet(),
        dependencies = dependencies,
        isComponentShard = index == 0
      )
      shards.add(shard)
      
      // Map bindings to shard index
      partition.forEach { key ->
        bindingToShard[key] = index
      }
    }
    
    return ShardingPlan(
      shards = shards,
      bindingToShard = bindingToShard,
      keysPerShard = keysPerShard
    )
  }

  /**
   * Calculates which bindings from other shards this shard depends on.
   */
  private fun calculateShardDependencies(
    shardBindings: List<IrTypeKey>,
    bindingGraph: IrBindingGraph,
    bindingToShard: Map<IrTypeKey, Int>
  ): Set<IrTypeKey> {
    val dependencies = mutableSetOf<IrTypeKey>()
    
    for (key in shardBindings) {
      val binding = bindingGraph.findBinding(key) ?: continue
      
      // Check each dependency
      for (dependency in binding.dependencies.toList()) {
        val depKey = dependency.typeKey
        // If dependency is in a different (earlier) shard, add it
        if (depKey in bindingToShard && bindingToShard[depKey] != bindingToShard[key]) {
          dependencies.add(depKey)
        }
      }
    }
    
    return dependencies
  }

  /**
   * Determines if a binding should be included in sharding.
   * 
   * Following Dagger, certain bindings stay in the main graph:
   * - BoundInstance bindings (constructor parameters)
   * - Alias bindings
   * - Module provisions (to avoid circular initialization)
   */
  private fun shouldIncludeInSharding(binding: IrBinding): Boolean {
    return when (binding) {
      is IrBinding.BoundInstance -> false // Keep in main graph
      is IrBinding.Alias -> false // Keep in main graph  
      is IrBinding.Provided -> {
        // Module provisions stay in main graph to avoid circular init
        // (Following Dagger's pattern)
        false
      }
      else -> true
    }
  }
}