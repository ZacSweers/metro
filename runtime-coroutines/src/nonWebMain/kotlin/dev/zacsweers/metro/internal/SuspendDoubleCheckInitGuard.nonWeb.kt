// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JVM/Native: a coroutine [Mutex] (a thread lock can't be held across a suspension point). One
 * caller computes; concurrent callers wait on the mutex and re-check publication. The mutex is not
 * reentrant, so cyclic initialization is detected by caller identity before locking.
 */
public actual open class SuspendDoubleCheckInitGuard actual constructor() {
  internal val mutex = Mutex()
}

internal actual suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T = mutex.withLock { block() }

internal actual suspend fun SuspendDoubleCheckInitGuard.initCallerIdentity(): Any =
  // The coroutine's Job when present; the context instance for Job-less coroutines (e.g.
  // `suspend fun main` or a bare `startCoroutine`) so cycle detection still works there.
  coroutineContext[Job] ?: coroutineContext
