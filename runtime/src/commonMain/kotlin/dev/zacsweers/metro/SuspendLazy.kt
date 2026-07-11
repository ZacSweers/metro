// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A value computed on demand in a suspend context and cached for later calls. */
@ExperimentalMetroCoroutinesApi
public interface SuspendLazy<out T> {
  /**
   * Returns the cached value, suspending to compute it when necessary. A failed or cancelled
   * computation is not cached.
   */
  public suspend fun value(): T

  /** Returns `true` after a value has been computed and cached. */
  public fun isInitialized(): Boolean
}
