// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import androidx.collection.IntObjectMap
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.parentGraphInstanceProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

/**
 * Context for generating expressions inside a shard or `SwitchingProvider`.
 *
 * When generating initialization code for shard properties, we need to access:
 * - Bound instances on the main graph class (via graphProperty)
 * - Properties in the same shard (via shardThisReceiver)
 * - Properties in other shards (via graphProperty.shardField.property)
 * - Properties on ancestor graphs (via graphProperty.parentGraphProperty chain)
 *
 * For `SwitchingProvider` context ([isSwitchingProvider] = true):
 * - All property access must go through [graphProperty] since SwitchingProvider is a nested class
 *   that holds a reference to the graph/shard, not the graph/shard itself.
 * - [currentShardIndex] should be set to [SWITCHING_PROVIDER_SHARD_INDEX] to indicate this.
 * - For SwitchingProvider inside a shard (not the main graph), [shardGraphProperty] must be set to
 *   provide the extra hop to reach the main graph for cross-shard access.
 *
 * This context provides the receivers needed to generate correct property access paths.
 */
internal class ShardExpressionContext(
  /**
   * Property on the shard/SwitchingProvider class storing the graph reference (for bound instances
   * and cross-shard access). Null if this shard only accesses its own properties (no cross-shard or
   * bound instance dependencies). For SwitchingProvider, this is always non-null as all access goes
   * through the graph property.
   */
  val graphProperty: IrProperty?,
  /** This shard's this receiver (for same-shard property access). */
  val shardThisReceiver: IrValueParameter,
  /**
   * Index of the current shard (to determine if a property is in the same shard). Use
   * [SWITCHING_PROVIDER_SHARD_INDEX] to indicate SwitchingProvider context where all property
   * access must go through [graphProperty].
   */
  val currentShardIndex: Int,
  /** Map of shard index to shard field property on the main class (for cross-shard access). */
  val shardFields: IntObjectMap<IrProperty>,
  /**
   * For extension graphs (inner classes), maps ancestor graph type keys to the property chain
   * needed to access that ancestor from this graph. For example, to access the parent graph from an
   * extension graph shard, use `this.graph.parentGraphProperty`. The value is a list of properties
   * to chain through (e.g., [parentGraphInstanceProperty]).
   */
  val ancestorGraphProperties: Map<IrTypeKey, List<IrProperty>> = emptyMap(),
  /**
   * For SwitchingProvider inside a shard (not the main graph), this is the shard's graph property
   * that provides access to the main graph. This enables cross-shard access via the path:
   * `this.graph.shardGraphProperty.shardField.property`
   * - SwitchingProvider.graph -> Shard
   * - Shard.shardGraphProperty -> MainGraph
   * - MainGraph.shardField -> OtherShard
   *
   * Null for SwitchingProvider in the main graph class (where `this.graph` is already the main
   * graph) or for non-SwitchingProvider shard contexts.
   */
  val shardGraphProperty: IrProperty? = null,
  /**
   * For SwitchingProvider context, the index of the shard containing the SwitchingProvider. This
   * enables same-shard optimization: when accessing a property in the same shard, we can use
   * `this.graph.property` directly instead of going through the main graph's shard field.
   *
   * Null for SwitchingProvider in the main graph class or for non-SwitchingProvider contexts.
   */
  val parentShardIndex: Int? = null,
) {
  /**
   * Whether this context is for SwitchingProvider. When true, all property access must go through
   * [graphProperty] since the dispatch receiver is the SwitchingProvider, not the graph/shard.
   */
  val isSwitchingProvider: Boolean
    get() = currentShardIndex == SWITCHING_PROVIDER_SHARD_INDEX

  companion object {
    /** Sentinel value for [currentShardIndex] to indicate SwitchingProvider context. */
    const val SWITCHING_PROVIDER_SHARD_INDEX = -1
  }
}
