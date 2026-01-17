// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

/** Models a generated shard class. */
internal data class Shard(
  val index: Int,
  val shardClass: IrClass,
  val bindings: List<ShardBinding>,
  /** Properties owned by this shard, keyed by binding contextual type key. */
  val properties: Map<IrContextualTypeKey, ShardProperty>,
  /** Graph parameter in the shard constructor. Null for graph-as-shard. */
  var graphParam: IrValueParameter?,
  /**
   * Graph property field on the shard class for storing the graph reference. Null for
   * graph-as-shard.
   */
  var graphProperty: IrProperty?,
  /** True if this shard is the graph class itself (no nested shard class). */
  val isGraphAsShard: Boolean,
  /** Name allocator for properties in this shard. Each shard has its own allocator. */
  val nameAllocator: NameAllocator,
)
