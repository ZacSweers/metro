// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.runtime.internal

import dev.zacsweers.metro.runtime.Provider

/**
 * A lightweight implementation of Provider for singleton caching without synchronization.
 *
 * This is designed for unscoped bindings where thread-safety is not required during initialization.
 * Unlike DoubleCheck, this implementation doesn't use synchronization, making it more efficient
 * for bindings that don't need thread-safe lazy initialization.
 *
 * Key differences from DoubleCheck:
 * - No synchronization overhead
 * - Lighter memory footprint
 * - Suitable for unscoped bindings that are initialized on first access
 * - NOT thread-safe for initialization (multiple threads may initialize simultaneously)
 *
 * This follows Dagger's pattern of using different caching strategies for different scoping needs.
 */
public class SingleCheck<T> private constructor(
  private val delegate: Provider<T>
) : Provider<T> {

  @Volatile
  private var instance: Any? = UNINITIALIZED

  override fun get(): T {
    val currentInstance = instance
    if (currentInstance !== UNINITIALIZED) {
      @Suppress("UNCHECKED_CAST")
      return currentInstance as T
    }

    // Not synchronized - multiple threads may enter here
    // This is acceptable for unscoped bindings
    val newInstance = delegate.get()
    instance = newInstance

    @Suppress("UNCHECKED_CAST")
    return newInstance as T
  }

  public companion object {
    private val UNINITIALIZED = Any()

    /**
     * Creates a SingleCheck provider that caches the result of the delegate.
     *
     * Use this for unscoped bindings that need caching but don't require
     * thread-safe initialization.
     */
    @JvmStatic
    public fun <T> provider(delegate: Provider<T>): Provider<T> {
      return if (delegate is SingleCheck<*>) {
        // Already wrapped, don't double-wrap
        delegate
      } else {
        SingleCheck(delegate)
      }
    }
  }
}
