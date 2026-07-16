// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

private val UNINITIALIZED = Any()

/**
 * A [SuspendLazy] implementation using safe publication via compare-and-set.
 *
 * Multiple threads may compute the value redundantly, but only the first to CAS wins. Modeled after
 * Kotlin stdlib's `SafePublicationLazyImpl`.
 */
internal class SafePublicationSuspendLazy<T>(initializer: suspend () -> T) :
  SuspendLazy<T>, SuspendProvider<T> {
  @Volatile private var initializer: (suspend () -> T)? = initializer
  @Volatile private var _value: Any? = UNINITIALIZED

  // This final field is required to enable safe publication of the constructed instance itself when
  // it is shared through a data race. Matches Kotlin stdlib's SafePublicationLazyImpl.
  private val final: Any = UNINITIALIZED

  override suspend fun invoke(): T = value()

  @Suppress("UNCHECKED_CAST")
  override suspend fun value(): T {
    val result = _value
    if (result !== UNINITIALIZED) {
      return result as T
    }

    val initializerRef = initializer
    // if the initializer is already null, another thread won the race
    if (initializerRef != null) {
      val newValue = initializerRef()
      if (valueUpdater.compareAndSet(this, UNINITIALIZED, newValue)) {
        initializer = null
        return newValue as T
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

  private companion object {
    private val valueUpdater =
      AtomicReferenceFieldUpdater.newUpdater(
        SafePublicationSuspendLazy::class.java,
        Any::class.java,
        "_value",
      )
  }
}
