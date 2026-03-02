// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import java.io.Serializable

private val UNINITIALIZED = Any()

internal class UnsafeSuspendLazy<T>(initializer: suspend () -> T) :
  SuspendLazy<T>, SuspendProvider<T>, Serializable {
  private var initializer: (suspend () -> T)? = initializer
  private var _value: Any? = UNINITIALIZED

  override suspend fun invoke(): T = value()

  override suspend fun value(): T {
    if (_value === UNINITIALIZED) {
      _value = initializer!!()
      initializer = null
    }
    @Suppress("UNCHECKED_CAST")
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
  }
}
