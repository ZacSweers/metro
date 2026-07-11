// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * Platform-specific guard for [SuspendDoubleCheck]'s first value publication, mirroring
 * [DoubleCheckInitGuard].
 *
 * This is private [SuspendDoubleCheck] machinery, not a reusable lock abstraction. Implementations
 * may rely on the fact that the guard is only used until `_value` is initialized.
 *
 * Unlike [DoubleCheckInitGuard], the guarded block suspends, so thread-based guards don't apply.
 * Both implementations are single-flight (one caller computes, concurrent callers suspend and share
 * the result):
 * - JVM/Native guard with a coroutine Mutex.
 * - JS/Wasm are single-threaded, so the lock is a plain flag plus a FIFO continuation queue, with
 *   no atomics, no parking, no kotlinx.coroutines dependency.
 */
public expect open class SuspendDoubleCheckInitGuard()

/** Runs [block] while holding this guard. */
internal expect suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T

/** Tracks the [SuspendDoubleCheck] initializers in the current call chain. */
internal class SuspendDoubleCheckInitialization(
  private val owner: SuspendDoubleCheck<*>,
  private val callerIdentity: Any,
  private val parent: SuspendDoubleCheckInitialization?,
) : AbstractCoroutineContextElement(Key) {
  internal companion object Key : CoroutineContext.Key<SuspendDoubleCheckInitialization>

  internal fun contains(owner: SuspendDoubleCheck<*>, callerIdentity: Any): Boolean {
    var current: SuspendDoubleCheckInitialization? = this
    while (current != null) {
      if (current.owner === owner && current.callerIdentity == callerIdentity) {
        return true
      }
      current = current.parent
    }
    return false
  }
}

/** Runs [block] with an initialization marker added to its coroutine context. */
internal suspend fun <T> withSuspendDoubleCheckInitialization(
  owner: SuspendDoubleCheck<*>,
  callerIdentity: Any,
  block: suspend () -> T,
): T = suspendCoroutine { continuation ->
  val initialization =
    SuspendDoubleCheckInitialization(
      owner = owner,
      callerIdentity = callerIdentity,
      parent = continuation.context[SuspendDoubleCheckInitialization],
    )
  block.startCoroutine(
    Continuation(continuation.context + initialization) { result ->
      continuation.resumeWith(result)
    }
  )
}

/**
 * An identity for the calling coroutine. It distinguishes a marker inherited by the same caller
 * from one inherited by a child coroutine.
 */
internal expect suspend fun SuspendDoubleCheckInitGuard.initCallerIdentity(): Any
