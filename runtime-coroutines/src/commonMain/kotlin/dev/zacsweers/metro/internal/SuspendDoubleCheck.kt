// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroSuspendApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroSuspendApi
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val UNINITIALIZED_SUSPEND = Any()

/**
 * A [SuspendProvider] implementation that memoizes the value returned from a delegate
 * [SuspendProvider] using coroutine-safe synchronization via [Mutex].
 *
 * The provider instance is released after it's called.
 *
 * Modeled after [BaseDoubleCheck] but adapted for suspend context. Key differences:
 * - Synchronization uses a coroutine [Mutex] (a thread lock can't be held across a suspension
 *   point). The mutex is **not reentrant**, so unlike [BaseDoubleCheck] a reentrant (cyclic)
 *   invocation can't be allowed to proceed into the initializer — it would deadlock instead.
 *   Reentrant calls from the *initializing coroutine itself* are detected via its [Job] identity
 *   and fail fast with an [IllegalStateException] pointing at the circular dependency.
 * - A failed initializer is NOT cached; the next caller retries (same as [BaseDoubleCheck]).
 * - If the initializing coroutine is cancelled mid-initialization, the mutex is released and the
 *   next waiter re-runs the initializer; the cache is never poisoned.
 */
public class SuspendDoubleCheck<T> private constructor(provider: SuspendProvider<T>) :
  SuspendProvider<T>, SuspendLazy<T> {
  // Stored as SuspendProvider (not `suspend () -> T`) so invocation dispatches through the
  // interface — on Kotlin/JS a fun interface instance is not a callable JS function, so invoking
  // it through the suspend function type fails at runtime.
  private var provider: SuspendProvider<T>? = provider
  @Volatile private var _value: Any? = UNINITIALIZED_SUSPEND
  private val mutex = Mutex()

  /**
   * The [Job] of the coroutine currently running the initializer, or null when no initialization is
   * in flight. Used to detect reentrant (cyclic) invocations that would otherwise suspend forever
   * on the non-reentrant [mutex]. Written only while holding the mutex; read on the unlocked fast
   * path (volatile for visibility).
   */
  @Volatile private var initializingJob: Job? = null

  override suspend fun invoke(): T {
    val result1 = _value
    if (result1 !== UNINITIALIZED_SUSPEND) {
      @Suppress("UNCHECKED_CAST")
      return result1 as T
    }

    val callerJob = coroutineContext[Job]
    check(callerJob == null || callerJob !== initializingJob) {
      "Scoped suspend provider was invoked recursively during its own initialization. " +
        "This is likely due to a circular dependency."
    }

    return mutex.withLock {
      val result2 = _value
      if (result2 !== UNINITIALIZED_SUSPEND) {
        @Suppress("UNCHECKED_CAST") (result2 as T)
      } else {
        initializingJob = callerJob
        try {
          val typedValue = provider!!()
          _value = typedValue
          // Null out the reference to the provider. We are never going to need it again, so we
          // can make it eligible for GC.
          provider = null
          typedValue
        } finally {
          initializingJob = null
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
        // Avoid double-wrapping a SuspendDoubleCheck — same pattern as DoubleCheck.provider
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendProvider<T>
      }
      return SuspendDoubleCheck(delegate.asSuspendProvider())
    }

    /** Returns a [SuspendLazy] that caches the value from the given delegate provider. */
    public fun <P : suspend () -> T, T> lazy(delegate: P): SuspendLazy<T> {
      if (delegate is SuspendDoubleCheck<*>) {
        // Avoid double-wrapping a SuspendDoubleCheck — same pattern as DoubleCheck.lazy
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
