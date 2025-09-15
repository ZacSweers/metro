// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * Constants used by the Metro compiler.
 */
internal object MetroConstants {
  /**
   * Maximum number of statements per method before splitting. Based on JVM limitations (64KB method size).
   *
   * Borrowed from Dagger:
   * https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
   */
  const val STATEMENTS_PER_METHOD = 25

  /**
   * Maximum expected number of shards for FIR generation.
   * This is used to pre-generate shard class skeletons.
   * Actual number of shards used will be determined by the sharding plan.
   */
  const val MAX_EXPECTED_SHARDS = 10

  /**
   * Default number of bindings per shard.
   * Can be overridden via compiler options.
   *
   * @see MetroOption.KEYS_PER_SHARD
   */
  const val DEFAULT_KEYS_PER_SHARD = 1000

  /**
   * Minimum number of bindings before sharding is considered.
   * Graphs smaller than this will not be sharded for efficiency.
   */
  const val MIN_BINDINGS_FOR_SHARDING = 100
}
