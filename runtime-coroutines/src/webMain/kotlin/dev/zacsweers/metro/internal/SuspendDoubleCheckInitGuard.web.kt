// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.coroutines.Continuation
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Web doesn't do multithreading, so mutual exclusion needs no atomics or parking, just a plain flag
 * and a FIFO queue of suspended waiters. One caller computes. Concurrent callers suspend and share
 * the result. Built entirely on stdlib continuations, which keeps the web variants of this module
 * free of any kotlinx.coroutines dependency.
 *
 * The lock is not reentrant. [SuspendDoubleCheck] detects cyclic initialization by caller identity
 * before locking. One divergence from the JVM/Native Mutex: a waiter whose coroutine is cancelled
 * while queued isn't released early. It resumes when the lock frees and observes the cancellation
 * at its next cancellable suspension point.
 */
public actual open class SuspendDoubleCheckInitGuard actual constructor() {
  internal var locked: Boolean = false
  internal val waiters = ArrayDeque<Continuation<Unit>>()
}

internal actual suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T {
  while (locked) {
    suspendCoroutine { continuation -> waiters.addLast(continuation) }
  }
  locked = true
  try {
    return block()
  } finally {
    locked = false
    // Wake the next waiter; it re-checks the published value inside its own guarded block (or
    // retries the initializer if this one failed). Each release wakes exactly one, FIFO.
    waiters.removeFirstOrNull()?.resume(Unit)
  }
}

internal actual suspend fun SuspendDoubleCheckInitGuard.initCallerIdentity(): Any = coroutineContext
