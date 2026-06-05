// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpinLockTest {
  private val nextThreadId = AtomicInteger(1)
  private val threadId = ThreadLocal.withInitial { nextThreadId.getAndIncrement() }

  @Test
  fun reentrant() {
    val lock = spinLock()
    var calls = 0

    lock.lock()
    try {
      calls++
      lock.lock()
      try {
        calls++
      } finally {
        lock.unlock()
      }
    } finally {
      lock.unlock()
    }

    assertEquals(2, calls)
  }

  @Test
  fun contendedLockBacksOffBeforeAcquire() {
    val sleeps = CopyOnWriteArrayList<UInt>()
    val lock = spinLock(sleep = sleeps::add)
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val acquired = AtomicBoolean(false)

    val holder = thread {
      lock.lock()
      try {
        locked.countDown()
        release.await(5, SECONDS)
      } finally {
        lock.unlock()
      }
    }

    assertTrue(locked.await(5, SECONDS))

    val waiter = thread {
      lock.lock()
      try {
        acquired.set(true)
      } finally {
        lock.unlock()
      }
    }

    eventually { sleeps.isNotEmpty() }
    assertFalse(acquired.get())

    release.countDown()
    holder.join(5_000)
    waiter.join(5_000)

    assertTrue(acquired.get())
    assertEquals(1u, sleeps.first())
  }

  @Test
  fun highContention() {
    val workerCount = 32
    val iterations = 2_000
    val backoffs = AtomicInteger(0)
    val lock =
      spinLock(
        sleep = {
          backoffs.incrementAndGet()
          Thread.yield()
        }
      )
    val ready = CountDownLatch(workerCount)
    val start = CountDownLatch(1)
    val activeInCriticalSection = AtomicInteger(0)
    val counter = AtomicInteger(0)
    val failure = AtomicReference<Throwable?>(null)

    val workers =
      List(workerCount) {
        thread {
          ready.countDown()
          start.await()
          repeat(iterations) {
            lock.lock()
            var enteredCriticalSection = false
            try {
              enteredCriticalSection = activeInCriticalSection.incrementAndGet() == 1
              check(enteredCriticalSection)
              repeat(4) {
                Thread.yield()
              }
              counter.incrementAndGet()
            } catch (t: Throwable) {
              failure.compareAndSet(null, t)
            } finally {
              if (enteredCriticalSection) {
                activeInCriticalSection.decrementAndGet()
              }
              lock.unlock()
            }
          }
        }
      }

    assertTrue(ready.await(5, SECONDS))
    start.countDown()
    workers.forEach {
      it.join(30_000)
      assertFalse(it.isAlive)
    }

    failure.get()?.let { throw it }
    assertEquals(0, activeInCriticalSection.get())
    assertTrue(backoffs.get() > 0)
    assertEquals(workerCount * iterations, counter.get())
  }

  private fun spinLock(
    useBackoff: Boolean = true,
    sleep: (UInt) -> Unit = {},
  ): SpinLock {
    return SpinLock(
      currentThreadId = { threadId.get() },
      useBackoff = useBackoff,
      sleep = sleep,
      assert = ::check,
    )
  }

  private fun eventually(condition: () -> Boolean) {
    val timeoutAt = System.nanoTime() + SECONDS.toNanos(5)
    while (!condition()) {
      check(System.nanoTime() < timeoutAt) { "condition was not met before timeout" }
      Thread.yield()
    }
  }
}
