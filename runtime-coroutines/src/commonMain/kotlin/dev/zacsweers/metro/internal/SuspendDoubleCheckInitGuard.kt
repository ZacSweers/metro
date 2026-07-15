// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * Platform-specific guard for [SuspendDoubleCheck]'s initialization, mirroring
 * [DoubleCheckInitGuard].
 *
 * This supports [SuspendDoubleCheck] and is not a general-purpose lock. Implementations may rely on
 * the guard being used only until `_value` is initialized.
 *
 * Unlike [DoubleCheckInitGuard], the guarded block suspends, so thread-based guards don't apply.
 * Both implementations provide single-flight initialization:
 * - JVM/Native use a coroutine Mutex.
 * - JS/Wasm use a flag and FIFO continuation queue with no kotlinx-coroutines dependency.
 */
public expect open class SuspendDoubleCheckInitGuard()

/** Runs [block] while holding this guard. */
internal expect suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T

/** Tracks the [SuspendDoubleCheck] initializers in the current call chain. */
internal class SuspendDoubleCheckInitialization(
  private val owner: SuspendDoubleCheck<*>,
  private val parent: SuspendDoubleCheckInitialization?,
) : AbstractCoroutineContextElement(Key) {
  internal companion object Key : CoroutineContext.Key<SuspendDoubleCheckInitialization>

  internal fun contains(owner: SuspendDoubleCheck<*>): Boolean {
    var current: SuspendDoubleCheckInitialization? = this
    while (current != null) {
      if (current.owner === owner) {
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
  block: suspend () -> T,
): T = suspendCoroutine { continuation ->
  val initialization =
    SuspendDoubleCheckInitialization(
      owner = owner,
      parent = continuation.context[SuspendDoubleCheckInitialization],
    )
  block.startCoroutine(
    Continuation(continuation.context + initialization) { result ->
      continuation.resumeWith(result)
    }
  )
}
