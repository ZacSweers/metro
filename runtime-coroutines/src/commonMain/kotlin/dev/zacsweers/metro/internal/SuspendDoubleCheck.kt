// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendLazy
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val UNINITIALIZED_SUSPEND = Any()

/**
 * A [SuspendProvider] implementation that memoizes the value returned from a delegate
 * [SuspendProvider]. The delegate is released after successful initialization.
 *
 * Modeled after [BaseDoubleCheck], with synchronization provided by a coroutine Mutex.
 *
 * Semantics on all platforms:
 * - Single-flight. One caller runs the initializer and concurrent callers suspend and share its
 *   result.
 * - A failed initialization is not cached. The next caller retries.
 * - Cancellation mid-initialization leaves the cache untouched. The next caller recomputes.
 * - A binding that resolves itself during its own initialization fails fast with a circular
 *   dependency error. The delegate runs with a context marker that is inherited by structured child
 *   coroutines. Independent calls outside that initialization chain still wait normally. Separate
 *   initialization chains can still deadlock if each holds one cache and requests the other.
 */
public class SuspendDoubleCheck<T> private constructor(provider: SuspendProvider<T>) :
  SuspendProvider<T>, SuspendLazy<T> {
  // Stored as SuspendProvider (not `suspend () -> T`) so invocation dispatches through the
  // interface. On Kotlin/JS a fun interface instance is not a callable JS function, so invoking
  // it through the suspend function type fails at runtime.
  private var provider: SuspendProvider<T>? = provider
  private val mutex = Mutex()
  @Volatile private var _value: Any? = UNINITIALIZED_SUSPEND

  override suspend fun invoke(): T {
    val result1 = _value
    if (result1 !== UNINITIALIZED_SUSPEND) {
      @Suppress("UNCHECKED_CAST")
      return result1 as T
    }

    val initialization = coroutineContext[SuspendDoubleCheckInitialization]
    check(initialization?.contains(this) != true) {
      "A suspend value was requested recursively while it was still being initialized. The " +
        "recursive request would wait for that same initialization, likely due to a circular " +
        "dependency."
    }

    return mutex.withLock {
      val result2 = _value
      if (result2 !== UNINITIALIZED_SUSPEND) {
        @Suppress("UNCHECKED_CAST") (result2 as T)
      } else {
        val typedValue = withSuspendDoubleCheckInitialization(this) { provider!!.invoke() }
        _value = typedValue
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
    public fun <T> provider(delegate: SuspendProvider<T>): SuspendProvider<T> {
      if (delegate is SuspendDoubleCheck<*>) {
        // Avoid double-wrapping a SuspendDoubleCheck, same pattern as DoubleCheck.provider
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendProvider<T>
      }
      return SuspendDoubleCheck(delegate)
    }

    /** Returns a [SuspendLazy] that caches the value from the given delegate provider. */
    public fun <T> lazy(delegate: SuspendProvider<T>): SuspendLazy<T> {
      if (delegate is SuspendLazy<*>) {
        // Avoids memoizing a value that is already memoized, same pattern as DoubleCheck.lazy. This
        // also covers SuspendDoubleCheck, which is itself a SuspendLazy.
        @Suppress("UNCHECKED_CAST")
        return delegate as SuspendLazy<T>
      }
      return SuspendDoubleCheck(delegate)
    }
  }
}

/** Tracks the [SuspendDoubleCheck] initializers in the current call chain. */
private class SuspendDoubleCheckInitialization(
  private val owner: SuspendDoubleCheck<*>,
  private val parent: SuspendDoubleCheckInitialization?,
) : AbstractCoroutineContextElement(Key) {
  // Cleared once the initialization attempt that created this marker completes. A coroutine
  // launched from inside the initializer inherits this element and keeps it forever, so a stale
  // marker from a finished attempt must not be mistaken for an active cycle when that child later
  // makes a legal request for the same binding.
  @Volatile private var active: Boolean = true

  companion object Key : CoroutineContext.Key<SuspendDoubleCheckInitialization>

  fun deactivate() {
    active = false
  }

  fun contains(owner: SuspendDoubleCheck<*>): Boolean {
    var current: SuspendDoubleCheckInitialization? = this
    while (current != null) {
      if (current.active && current.owner === owner) {
        return true
      }
      current = current.parent
    }
    return false
  }
}

/** Runs [block] with an initialization marker added to its coroutine context. */
private suspend fun <T> withSuspendDoubleCheckInitialization(
  owner: SuspendDoubleCheck<*>,
  block: suspend () -> T,
): T = suspendCoroutine { continuation ->
  val initialization =
    SuspendDoubleCheckInitialization(
      owner = owner,
      parent = continuation.context[SuspendDoubleCheckInitialization],
    )
  block.startCoroutine(
    Continuation(continuation.context + initialization) { result ->
      // Invalidate the marker once this attempt finishes so children that inherited it are not
      // blocked by a stale cycle check on a later retry.
      initialization.deactivate()
      continuation.resumeWith(result)
    }
  )
}
