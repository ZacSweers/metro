// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val UNINITIALIZED_SUSPEND = Any()

/**
 * A [SuspendProvider] implementation that memoizes the value returned from a delegate
 * [SuspendProvider] using coroutine-safe synchronization via [Mutex].
 *
 * The provider instance is released after it's called.
 *
 * Modeled after [BaseDoubleCheck] but adapted for suspend context.
 */
public class SuspendDoubleCheck<T> private constructor(provider: suspend () -> T) :
  SuspendProvider<T>, SuspendLazy<T> {
  private var provider: (suspend () -> T)? = provider
  @Volatile private var _value: Any? = UNINITIALIZED_SUSPEND
  private val mutex = Mutex()

  override suspend fun invoke(): T {
    val result1 = _value
    if (result1 !== UNINITIALIZED_SUSPEND) {
      @Suppress("UNCHECKED_CAST")
      return result1 as T
    }

    return mutex.withLock {
      val result2 = _value
      if (result2 !== UNINITIALIZED_SUSPEND) {
        @Suppress("UNCHECKED_CAST") (result2 as T)
      } else {
        val typedValue = provider!!()
        _value = reentrantCheck(_value, typedValue)
        // Null out the reference to the provider. We are never going to need it again, so we
        // can make it eligible for GC.
        provider = null
        typedValue
      }
    }
  }

  override suspend fun value(): T = invoke()

  override fun isInitialized(): Boolean = _value !== UNINITIALIZED_SUSPEND

  override fun toString(): String =
    if (isInitialized()) {
      "SuspendDoubleCheck(value=$_value)"
    } else {
      "SuspendDoubleCheck(value=<not initialized>)"
    }

  public companion object {
    /** Returns a [SuspendProvider] that caches the value from the given delegate provider. */
    public fun <P : suspend () -> T, T> provider(delegate: P): SuspendProvider<T> {
      if (delegate is SuspendDoubleCheck<*>) {
        // Avoid double-wrapping a SuspendDoubleCheck — same pattern as DoubleCheck.provider
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendProvider<T>
      }
      return SuspendDoubleCheck(delegate)
    }

    /** Returns a [SuspendLazy] that caches the value from the given delegate provider. */
    public fun <P : suspend () -> T, T> lazy(delegate: P): SuspendLazy<T> {
      if (delegate is SuspendDoubleCheck<*>) {
        // Avoid double-wrapping a SuspendDoubleCheck — same pattern as DoubleCheck.lazy
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendLazy<T>
      }
      return SuspendDoubleCheck(delegate)
    }
  }
}

/**
 * Checks to see if creating the new instance has resulted in a recursive call. If it has, and the
 * new instance is the same as the current instance, return the instance. However, if the new
 * instance differs from the current instance, an [IllegalStateException] is thrown.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun reentrantCheck(currentInstance: Any?, newInstance: Any?): Any? {
  val isReentrant = currentInstance !== UNINITIALIZED_SUSPEND
  check(!isReentrant || currentInstance == newInstance) {
    "Scoped suspend provider was invoked recursively returning different results: $currentInstance & $newInstance. This is likely due to a circular dependency."
  }
  return newInstance
}
