// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A suspend analogue to [Lazy]. The value is computed lazily, in a suspend context, on first
 * [value] call.
 */
@ExperimentalMetroCoroutinesApi
public interface SuspendLazy<out T> {
  /**
   * Returns the lazily-computed value, suspending if necessary to compute it for the first time.
   */
  public suspend fun value(): T

  /** Returns `true` if the value has been computed. */
  public fun isInitialized(): Boolean
}
