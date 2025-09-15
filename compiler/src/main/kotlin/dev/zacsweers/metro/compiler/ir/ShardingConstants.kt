// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

/**
 * Shared constants for graph sharding implementation.
 *
 * These constants control various thresholds and limits used throughout the sharding system
 * to ensure generated code stays within JVM limitations.
 */
internal object ShardingConstants {
  /**
   * Maximum number of field initialization statements per method to avoid 64KB method size limit.
   *
   * This limit applies to both main graph initialization and shard initialization methods.
   * When exceeded, the initialization logic is split into multiple `initializePart{N}()` methods.
   *
   * Based on Dagger's implementation:
   * https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
   */
  const val STATEMENTS_PER_METHOD = 25

  /**
   * Default number of bindings per shard when sharding is enabled but no explicit value is configured.
   *
   * This provides a reasonable balance between shard count and shard size for typical applications.
   */
  const val DEFAULT_KEYS_PER_SHARD = 100

  /**
   * Minimum number of bindings required before sharding is beneficial.
   *
   * Below this threshold, the overhead of sharding outweighs the benefits.
   */
  const val MIN_BINDINGS_FOR_SHARDING = 50
}
