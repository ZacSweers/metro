// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import java.io.Serializable
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.concurrent.Volatile

private val UNINITIALIZED = Any()

internal class SafePublicationSuspendLazy<T>(initializer: suspend () -> T) :
  SuspendLazy<T>, SuspendProvider<T>, Serializable {
  @Volatile private var initializer: (suspend () -> T)? = initializer
  @Volatile private var _value: Any? = UNINITIALIZED

  override suspend fun invoke(): T = value()

  @Suppress("UNCHECKED_CAST")
  override suspend fun value(): T {
    val result = _value
    if (result !== UNINITIALIZED) {
      return result as T
    }

    val initializerValue = initializer
    // if we see null in initializer here, it means that the value is already set by another thread
    if (initializerValue != null) {
      val newValue = initializerValue()
      if (valueUpdater.compareAndSet(this, UNINITIALIZED, newValue)) {
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

  private fun writeReplace(): Any = InitializedSuspendLazy(value = _value)

  private companion object {
    private const val serialVersionUID: Long = 1L

    private val valueUpdater =
      AtomicReferenceFieldUpdater.newUpdater(
        SafePublicationSuspendLazy::class.java,
        Any::class.java,
        "_value",
      )
  }
}
