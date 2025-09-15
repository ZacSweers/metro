// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.sharding

/**
 * Constants used for sharding configuration and generation.
 */
internal object ShardingConstants {
  /**
   * Maximum number of statements per method before splitting.
   * Based on JVM limitations (64KB method size).
   */
  const val STATEMENTS_PER_METHOD = 100

  /**
   * Maximum expected number of shards for FIR generation.
   * This is used to pre-generate shard class skeletons.
   * Actual number of shards used will be determined by the sharding plan.
   */
  const val MAX_EXPECTED_SHARDS = 10

  /**
   * Default number of bindings per shard.
   * Can be overridden via compiler options.
   */
  const val DEFAULT_KEYS_PER_SHARD = 150

  /**
   * Minimum number of bindings before sharding is considered.
   * Graphs smaller than this will not be sharded for efficiency.
   */
  const val MIN_BINDINGS_FOR_SHARDING = 100
}