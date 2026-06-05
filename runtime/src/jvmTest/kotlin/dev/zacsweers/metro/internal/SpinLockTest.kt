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

    assertEquals(2, calls, "Reentrant locking should execute both critical sections")
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

    assertTrue(locked.await(5, SECONDS), "Holder thread did not acquire the lock")

    val waiter = thread {
      lock.lock()
      try {
        acquired.set(true)
      } finally {
        lock.unlock()
      }
    }

    eventually { sleeps.isNotEmpty() }
    assertFalse(acquired.get(), "Waiter acquired the lock before the holder released it")

    release.countDown()
    holder.join(5_000)
    waiter.join(5_000)

    assertTrue(acquired.get(), "Waiter did not acquire the lock after release")
    assertEquals(1u, sleeps.first(), "Backoff should start with a 1 microsecond sleep")
  }

  @Test
  fun highContention() {
    val workerCount = 16
    val iterations = 500
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
    val done = CountDownLatch(workerCount)
    val activeInCriticalSection = AtomicInteger(0)
    val counter = AtomicInteger(0)
    val failure = AtomicReference<Throwable?>(null)

    val workers =
      List(workerCount) { workerIndex ->
        thread {
          ready.countDown()
          start.await()
          try {
            repeat(iterations) {
              lock.lock()
              var enteredCriticalSection = false
              try {
                enteredCriticalSection = activeInCriticalSection.incrementAndGet() == 1
                check(enteredCriticalSection) {
                  "Worker $workerIndex entered the critical section concurrently"
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
          } finally {
            done.countDown()
          }
        }
      }

    assertTrue(ready.await(5, SECONDS), "Workers did not become ready before timeout")
    start.countDown()
    assertTrue(
      done.await(30, SECONDS),
      "Timed out waiting for workers. completed=${workerCount - done.count}, counter=${counter.get()}, backoffs=${backoffs.get()}, failure=${failure.get()}",
    )
    workers.forEach {
      it.join(5_000)
      assertFalse(it.isAlive, "Worker thread ${it.name} did not stop after completion")
    }

    failure.get()?.let { throw it }
    assertEquals(
      0,
      activeInCriticalSection.get(),
      "No worker should remain in the critical section after completion",
    )
    assertTrue(backoffs.get() > 0, "High contention should trigger at least one backoff")
    assertEquals(
      workerCount * iterations,
      counter.get(),
      "Each worker should complete every critical-section iteration",
    )
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
