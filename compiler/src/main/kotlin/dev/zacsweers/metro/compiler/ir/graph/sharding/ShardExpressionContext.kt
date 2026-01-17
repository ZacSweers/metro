// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.parentGraphInstanceProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

/**
 * Context for generating expressions inside a shard.
 *
 * When generating initialization code for shard properties, we need to access:
 * - Bound instances on the main graph class (via graphProperty)
 * - Properties in the same shard (via shardThisReceiver)
 * - Properties in other shards (via graphProperty.shardField.property)
 * - Properties on ancestor graphs (via graphProperty.parentGraphProperty chain)
 *
 * This context provides the receivers needed to generate correct property access paths.
 */
internal class ShardExpressionContext(
  /**
   * Property on the shard class storing the graph reference (for bound instances and cross-shard
   * access). Null if this shard only accesses its own properties (no cross-shard or bound instance
   * dependencies).
   */
  val graphProperty: IrProperty?,
  /** This shard's this receiver (for same-shard property access). */
  val shardThisReceiver: IrValueParameter,
  /** Index of the current shard (to determine if a property is in the same shard). */
  val currentShardIndex: Int,
  /** Map of shard index to shard field property on the main class (for cross-shard access). */
  val shardFields: Map<Int, IrProperty>,
  /**
   * For extension graphs (inner classes), maps ancestor graph type keys to the property chain
   * needed to access that ancestor from this graph. For example, to access the parent graph from an
   * extension graph shard, use `this.graph.parentGraphProperty`. The value is a list of properties
   * to chain through (e.g., [parentGraphInstanceProperty]).
   */
  val ancestorGraphProperties: Map<IrTypeKey, List<IrProperty>> = emptyMap(),
)
