// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.AtomicReference

private val UNINITIALIZED = Any()

/**
 * A [SuspendLazy] implementation using safe publication via compare-and-set.
 *
 * Multiple threads may compute the value redundantly, but only the first to CAS wins. Modeled after
 * Kotlin stdlib's `SafePublicationLazyImpl`.
 */
internal class SafePublicationSuspendLazy<T>(initializer: suspend () -> T) :
  SuspendLazy<T>, SuspendProvider<T> {
  private var initializer: (suspend () -> T)? = initializer
  private val atomicValue = AtomicReference<Any?>(UNINITIALIZED)

  override suspend fun invoke(): T = value()

  @Suppress("UNCHECKED_CAST")
  override suspend fun value(): T {
    val result = atomicValue.value
    if (result !== UNINITIALIZED) {
      return result as T
    }

    val initializerRef = initializer
    // if the initializer is already null, another thread won the race
    if (initializerRef != null) {
      val newValue = initializerRef()
      if (atomicValue.compareAndSet(UNINITIALIZED, newValue)) {
        initializer = null
        return newValue
      }
    }

    return atomicValue.value as T
  }

  override fun isInitialized(): Boolean = atomicValue.value !== UNINITIALIZED

  override fun toString(): String =
    if (isInitialized()) {
      "SuspendLazy(value=${atomicValue.value})"
    } else {
      "SuspendLazy(value=<not initialized>)"
    }
}
