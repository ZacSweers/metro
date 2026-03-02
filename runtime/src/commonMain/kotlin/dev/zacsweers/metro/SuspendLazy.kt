// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A suspend analogue to [Lazy]. Represents a value that is computed lazily in a suspend context.
 *
 * This is the suspend counterpart to [Lazy], just as [SuspendProvider] is the suspend counterpart
 * to [Provider].
 */
public interface SuspendLazy<out T> {
  /**
   * Returns the lazily-computed value, suspending if necessary to compute it for the first time.
   */
  public suspend fun value(): T

  /** Returns `true` if the value has been computed. */
  public fun isInitialized(): Boolean
}
