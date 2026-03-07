// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableIntSet
import androidx.collection.MutableObjectIntMap
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Tracks binding-to-shard assignments and cross-shard dependencies.
 *
 * Used during sharding to determine which bindings belong to which shard and what dependencies
 * exist between shards (for constructor parameter ordering).
 */
internal class ShardLookup {
  /** Maps each typeKey to the shard index that owns it. */
  private val bindingToShard = MutableObjectIntMap<IrTypeKey>()

  /** Maps shard index to the set of shard indices it depends on. */
  private val shardDependencies = MutableIntObjectMap<MutableIntSet>()

  /** Shards that need access to the graph reference (for cross-shard or bound instance access). */
  private val shardsNeedingGraphAccess = MutableIntSet()

  /** Assigns a binding to a shard. */
  fun assignToShard(typeKey: IrTypeKey, shardIndex: Int) {
    bindingToShard[typeKey] = shardIndex
  }

  /** Gets the shard index for a binding, or -1 if not assigned. */
  fun getShardIndex(typeKey: IrTypeKey): Int = bindingToShard.getOrDefault(typeKey, -1)

  /** Records that [fromShard] depends on [toShard]. */
  fun addShardDependency(fromShard: Int, toShard: Int) {
    if (fromShard != toShard) {
      shardDependencies.getOrPut(fromShard, ::MutableIntSet).add(toShard)
    }
  }

  /** Marks that [shardIndex] needs access to the graph reference. */
  fun markNeedsGraphAccess(shardIndex: Int) {
    shardsNeedingGraphAccess += shardIndex
  }

  /** Returns true if [shardIndex] needs a graph property for accessing dependencies. */
  fun needsGraphAccess(shardIndex: Int): Boolean = shardIndex in shardsNeedingGraphAccess
}
