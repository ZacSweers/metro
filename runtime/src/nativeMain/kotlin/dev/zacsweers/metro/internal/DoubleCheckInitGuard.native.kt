// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * A reentrant initialization guard. Not a general-purpose lock: it protects the one-time
 * initialization in [BaseDoubleCheck], so the contended path is rare (a first-read race per
 * instance) and the uncontended fast path never touches any process-wide state.
 *
 * The only per-instance state is [owner]. Contended callers park on a single process-wide pthread
 * mutex/condvar pair (see [awaitGuardRelease]) rather than spinning, so waiters consume no CPU
 * while blocked. On Apple platforms, waiters additionally donate their QoS class to the
 * initializing thread while parked.
 */
public actual open class DoubleCheckInitGuard actual constructor() {
  /** 0 when unowned, otherwise the [currentThreadId] of the thread currently initializing. */
  internal val owner: AtomicLong = AtomicLong(0)
}

@OptIn(ExperimentalContracts::class)
internal actual inline fun <T> DoubleCheckInitGuard.guarded(block: () -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  val self = currentThreadId()
  if (owner.load() == self) {
    // Same-thread reentrancy from a recursive provider: run the block directly and let
    // reentrantCheck handle the recursion semantics. No depth counter is needed because recursive
    // frames do not acquire or release the guard; only the frame that won the CAS clears owner.
    return block()
  }
  while (true) {
    if (owner.compareAndSet(0L, self)) {
      try {
        return block()
      } finally {
        owner.store(0L)
        wakeGuardWaiters()
      }
    }
    awaitGuardRelease(this)
  }
}

/** Number of threads currently parked in [awaitGuardRelease], across all guards. */
private val guardWaiters = AtomicInt(0)

/** Parks the current thread until [guard] is released. May wake spuriously; callers loop. */
internal fun awaitGuardRelease(guard: DoubleCheckInitGuard) {
  parkerLock()
  guardWaiters.incrementAndFetch()
  try {
    while (true) {
      val owner = guard.owner.load()
      if (owner == 0L) break
      parkerWait(owner)
    }
  } finally {
    guardWaiters.decrementAndFetch()
    parkerUnlock()
  }
}

/** Wakes all threads parked in [awaitGuardRelease]; each rechecks its own guard. */
internal fun wakeGuardWaiters() {
  // Skip the parker entirely when nothing is waiting (the common case). This unsynchronized
  // read is safe: waiters increment guardWaiters before reading owner under the parker mutex,
  // and releasers clear owner before this read. Both are seq-cst, so if we read 0 here, a
  // late-arriving waiter is guaranteed to observe owner == 0 and not park.
  if (guardWaiters.load() == 0) return
  parkerLock()
  parkerBroadcast()
  parkerUnlock()
}

/**
 * A stable, non-zero id for the current thread. On Apple platforms this is the thread's `pthread_t`
 * so that [parkerWait] can donate QoS to the guard owner; elsewhere it's a monotonic counter.
 */
internal expect fun currentThreadId(): Long

/** Locks the process-wide parker mutex. */
internal expect fun parkerLock()

/** Unlocks the process-wide parker mutex. */
internal expect fun parkerUnlock()

/**
 * Waits on the process-wide parker condvar; must be called with the parker mutex held. [lockOwner]
 * is the [currentThreadId] of the thread currently owning the awaited guard; Apple uses it (as a
 * `pthread_t`) for QoS donation.
 */
internal expect fun parkerWait(lockOwner: Long)

/** Broadcasts the process-wide parker condvar; must be called with the parker mutex held. */
internal expect fun parkerBroadcast()
