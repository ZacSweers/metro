// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.Volatile

private val UNINITIALIZED_SUSPEND = Any()

/**
 * A [SuspendProvider] implementation that memoizes the value returned from a delegate
 * [SuspendProvider]. The delegate is released after it's called.
 *
 * Modeled after [BaseDoubleCheck], with synchronization provided by the
 * [SuspendDoubleCheckInitGuard] superclass (a coroutine Mutex on JVM/Native, a single-threaded
 * continuation-queue lock on JS/Wasm).
 *
 * Semantics on all platforms:
 * - Single-flight. One caller runs the initializer and concurrent callers suspend and share its
 *   result.
 * - A failed initialization is not cached. The next caller retries.
 * - Cancellation mid-initialization leaves the cache untouched. The next caller recomputes.
 * - A binding that resolves itself during its own initialization fails fast with a circular
 *   dependency error, detected by caller identity ([initCallerIdentity]). The guard is not
 *   reentrant, so proceeding would deadlock or recurse forever.
 */
public class SuspendDoubleCheck<T> private constructor(provider: SuspendProvider<T>) :
  SuspendDoubleCheckInitGuard(), SuspendProvider<T>, SuspendLazy<T> {
  // Stored as SuspendProvider (not `suspend () -> T`) so invocation dispatches through the
  // interface. On Kotlin/JS a fun interface instance is not a callable JS function, so invoking
  // it through the suspend function type fails at runtime.
  private var provider: SuspendProvider<T>? = provider
  @Volatile private var _value: Any? = UNINITIALIZED_SUSPEND

  /**
   * An identity for the coroutine currently running the initializer, or null when no initialization
   * is in flight. Written only inside the guard; read on the unguarded fast path (volatile for
   * visibility).
   */
  @Volatile private var initializingCaller: Any? = null

  override suspend fun invoke(): T {
    val result1 = _value
    if (result1 !== UNINITIALIZED_SUSPEND) {
      @Suppress("UNCHECKED_CAST")
      return result1 as T
    }

    val caller = initCallerIdentity()
    check(caller !== initializingCaller) {
      "Scoped suspend provider was invoked recursively during its own initialization. " +
        "This is likely due to a circular dependency."
    }

    return guardedSuspend {
      val result2 = _value
      if (result2 !== UNINITIALIZED_SUSPEND) {
        @Suppress("UNCHECKED_CAST") (result2 as T)
      } else {
        initializingCaller = caller
        try {
          val typedValue = provider!!()
          _value = typedValue
          // Null out the reference to the provider. We are never going to need it again, so we
          // can make it eligible for GC.
          provider = null
          typedValue
        } finally {
          initializingCaller = null
        }
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
        // Avoid double-wrapping a SuspendDoubleCheck, same pattern as DoubleCheck.provider
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendProvider<T>
      }
      return SuspendDoubleCheck(delegate.asSuspendProvider())
    }

    /** Returns a [SuspendLazy] that caches the value from the given delegate provider. */
    public fun <P : suspend () -> T, T> lazy(delegate: P): SuspendLazy<T> {
      if (delegate is SuspendDoubleCheck<*>) {
        // Avoid double-wrapping a SuspendDoubleCheck, same pattern as DoubleCheck.lazy
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendLazy<T>
      }
      return SuspendDoubleCheck(delegate.asSuspendProvider())
    }

    private fun <T> (suspend () -> T).asSuspendProvider(): SuspendProvider<T> {
      @Suppress("UNCHECKED_CAST")
      return this as? SuspendProvider<T> ?: SuspendProvider { invoke() }
    }
  }
}
