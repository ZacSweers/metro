// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider

private val UNINITIALIZED = Any()

/**
 * Safe publication for single-threaded web platforms.
 *
 * Although execution is single-threaded, one caller can suspend inside the initializer before it
 * publishes a value. Another caller can then see the lazy as uninitialized and start the same
 * initializer. When either call completes, it checks the value again before publishing so all
 * callers observe the first completed value.
 */
internal class SafePublicationSuspendLazy<T>(initializer: suspend () -> T) :
  SuspendLazy<T>, SuspendProvider<T> {
  private var initializer: (suspend () -> T)? = initializer
  private var _value: Any? = UNINITIALIZED

  override suspend fun invoke(): T = value()

  @Suppress("UNCHECKED_CAST")
  override suspend fun value(): T {
    val result = _value
    if (result !== UNINITIALIZED) {
      return result as T
    }

    val initializerRef = initializer
    if (initializerRef != null) {
      val newValue = initializerRef()
      if (_value === UNINITIALIZED) {
        _value = newValue
        initializer = null
        return newValue
      }
    }

    return _value as T
  }

  override fun isInitialized(): Boolean = _value !== UNINITIALIZED

  override fun toString(): String =
    if (isInitialized()) {
      "SuspendLazy(value=$_value)"
    } else {
      "SuspendLazy(value=<not initialized>)"
    }
}
