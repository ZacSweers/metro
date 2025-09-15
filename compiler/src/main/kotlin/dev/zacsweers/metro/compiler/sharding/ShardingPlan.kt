// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.sharding

import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Represents a plan for sharding a dependency graph into multiple classes.
 * 
 * Following Dagger's approach, this plan partitions bindings across shards while:
 * - Keeping strongly connected components (cycles) together
 * - Maintaining reverse topological order (Shard{i} doesn't depend on Shard{i+j})
 * - Respecting the keysPerShard threshold
 *
 * @property shards List of shards in initialization order (shard 0 is the component shard)
 * @property bindingToShard Maps each binding key to its assigned shard index
 * @property keysPerShard The threshold used for partitioning
 */
internal data class ShardingPlan(
  val shards: List<Shard>,
  val bindingToShard: Map<IrTypeKey, Int>,
  val keysPerShard: Int
) {
  /**
   * Represents a single shard containing a subset of bindings.
   *
   * @property index The shard index (0 for component shard, 1+ for nested shards)
   * @property bindings Set of binding keys assigned to this shard
   * @property dependencies Set of binding keys this shard depends on from other shards
   * @property isComponentShard True if this is the main component shard (index 0)
   * @property requiredModules Set of module types required by this shard (added by Phase 3)
   */
  internal data class Shard(
    val index: Int,
    val bindings: Set<IrTypeKey>,
    val dependencies: Set<IrTypeKey>,
    val isComponentShard: Boolean = false,
    val requiredModules: Set<IrTypeKey> = emptySet()
  ) {
    /**
     * Returns the name for this shard class.
     * Component shard (0) uses the graph name, others are named Shard1, Shard2, etc.
     */
    internal fun shardClassName(): String = 
      if (isComponentShard) "" else "Shard$index"
  }

  /**
   * Returns the shard containing the given binding key.
   */
  internal fun shardFor(key: IrTypeKey): Shard? {
    val index = bindingToShard[key] ?: return null
    return shards.getOrNull(index)
  }

  /**
   * Returns true if sharding is needed (more than one shard).
   */
  internal fun requiresSharding(): Boolean = shards.size > 1

  /**
   * Returns all non-component shards (i.e., nested shard classes to generate).
   */
  internal fun additionalShards(): List<Shard> = shards.drop(1)
  
  /**
   * Returns the shard index for a given binding, or null if not sharded.
   * Added by Phase 3 for compatibility.
   */
  internal fun getShardForBinding(typeKey: IrTypeKey): Int? = bindingToShard[typeKey]
  
  /**
   * Returns true if the binding is in a different shard than the current one.
   * Added by Phase 3 for cross-shard detection.
   */
  internal fun isCrossShardAccess(typeKey: IrTypeKey, currentShard: Int): Boolean {
    val targetShard = getShardForBinding(typeKey) ?: 0
    return targetShard != currentShard
  }

  internal companion object {
    /**
     * Creates a no-sharding plan where all bindings are in the component shard.
     */
    internal fun noSharding(bindings: Set<IrTypeKey>): ShardingPlan {
      val componentShard = Shard(
        index = 0,
        bindings = bindings,
        dependencies = emptySet(),
        isComponentShard = true
      )
      return ShardingPlan(
        shards = listOf(componentShard),
        bindingToShard = bindings.associateWith { 0 },
        keysPerShard = bindings.size
      )
    }
  }
}