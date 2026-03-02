// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.AtomicReference

private val UNINITIALIZED = Any()

internal class SafePublicationSuspendLazy<T>(initializer: suspend () -> T) :
  SuspendLazy<T>, SuspendProvider<T> {
  private var initializer = AtomicReference<(suspend () -> T)?>(initializer)
  private var valueRef = AtomicReference<Any?>(UNINITIALIZED)

  override suspend fun invoke(): T = value()

  @Suppress("UNCHECKED_CAST")
  override suspend fun value(): T {
    val value = valueRef.value
    if (value !== UNINITIALIZED) {
      return value as T
    }

    val initializerValue = initializer.value
    // if we see null in initializer here, it means that the value is already set by another thread
    if (initializerValue != null) {
      val newValue = initializerValue()
      if (valueRef.compareAndSet(UNINITIALIZED, newValue)) {
        initializer.value = null
        return newValue
      }
    }
    return valueRef.value as T
  }

  override fun isInitialized(): Boolean = valueRef.value !== UNINITIALIZED

  override fun toString(): String =
    if (isInitialized()) {
      "SuspendLazy(value=${valueRef.value})"
    } else {
      "SuspendLazy(value=<not initialized>)"
    }
}
