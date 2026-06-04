// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalAtomicApi::class)
class LockTest {
  @Test
  fun reentrant() {
    val lock = Lock()
    var calls = 0

    lock.withLock {
      calls++
      lock.withLock { calls++ }
    }

    assertEquals(2, calls)
  }

  @Test
  fun contendedLockAcquiresAfterUnlock() = runBlocking {
    val lock = Lock()
    val state = AtomicInt(0)
    val release = AtomicInt(0)

    val holder =
      async(Dispatchers.Default) {
        lock.lock()
        try {
          state.incrementAndFetch()
          while (release.load() == 0) {
            // Keep the lock held by this worker thread until the test releases it.
          }
        } finally {
          lock.unlock()
        }
      }

    lateinit var waiter: Deferred<Unit>
    try {
      withTimeout(5_000.milliseconds) {
        while (state.load() == 0) {
          delay(1.milliseconds)
        }
      }

      waiter =
        async(Dispatchers.Default) {
          state.incrementAndFetch()
          lock.withLock { state.incrementAndFetch() }
        }

      withTimeout(5_000.milliseconds) {
        while (state.load() < 2) {
          delay(1.milliseconds)
        }
      }

      delay(50.milliseconds)
      assertEquals(2, state.load())
    } finally {
      release.store(1)
      holder.await()
    }

    waiter.await()

    assertEquals(3, state.load())
  }
}
