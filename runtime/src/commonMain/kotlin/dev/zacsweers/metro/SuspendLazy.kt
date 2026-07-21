// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.SuspendInstanceFactory

/** A value obtained in a suspend context and cached for later calls. */
@ExperimentalMetroCoroutinesApi
public interface SuspendLazy<out T> {
  /**
   * Returns the cached value, suspending while initialization runs when necessary. A failed or
   * cancelled initialization is not cached.
   */
  public suspend fun value(): T

  /** Returns `true` when a cached value is available. */
  public fun isInitialized(): Boolean
}

/** Returns an initialized [SuspendLazy] containing [value]. */
@ExperimentalMetroCoroutinesApi
public fun <T> suspendLazyOf(value: T): SuspendLazy<T> = SuspendInstanceFactory(value)
