// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Guard for [SuspendDoubleCheck]'s initialization, mirroring [DoubleCheckInitGuard].
 *
 * This supports [SuspendDoubleCheck] and is not a general-purpose lock. It is used only until
 * `_value` is initialized.
 *
 * Unlike [DoubleCheckInitGuard], the guarded block suspends, so thread-based guards don't apply. A
 * coroutine Mutex provides single-flight initialization.
 */
public open class SuspendDoubleCheckInitGuard {
  private val mutex = Mutex()

  /** Runs [block] while holding this guard. */
  internal suspend fun <T> guardedSuspend(block: suspend () -> T): T = mutex.withLock { block() }
}

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
