// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import java.util.concurrent.atomic.AtomicInteger
import junit.framework.TestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Gates control overlap, ordering, and cleanup independently of file analysis. */
class OrderedSourceScanTest : TestCase() {
  fun testOneWorkerReadsAndAcceptsOnTheCaller() = runBlocking {
    val caller = Thread.currentThread()
    val events = mutableListOf<String>()
    scanInOrder(
      items = listOf(1, 2, 3),
      parallelism = 1,
      read = { item ->
        assertSame(caller, Thread.currentThread())
        events += "read $item"
        item * 2
      },
      accept = { item, result ->
        assertSame(caller, Thread.currentThread())
        events += "accept $item=$result"
      },
    )
    assertEquals(
      listOf("read 1", "accept 1=2", "read 2", "accept 2=4", "read 3", "accept 3=6"),
      events,
    )
  }

  fun testParallelReadsHaveBoundedBacklogAndAcceptInOrderOnTheCaller() = runBlocking {
    withTimeout(10_000) {
      val caller = Thread.currentThread()
      val firstStarted = CompletableDeferred<Unit>()
      val releaseFirst = CompletableDeferred<Unit>()
      val windowFilled = CompletableDeferred<Unit>()
      val beyondWindowStarted = CompletableDeferred<Unit>()
      val active = AtomicInteger()
      val peak = AtomicInteger()
      val accepted = mutableListOf<Pair<Int, Int?>>()
      val scan = async {
        scanInOrder(
          items = (0..9).toList(),
          parallelism = 2,
          read = { item ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
              if (item == 0) {
                firstStarted.complete(Unit)
                releaseFirst.await()
              } else {
                firstStarted.await()
              }
              if (item == 3) windowFilled.complete(Unit)
              if (item == 4) beyondWindowStarted.complete(Unit)
              if (item == 5) null else item * 2
            } finally {
              active.decrementAndGet()
            }
          },
          accept = { item, result ->
            assertSame(caller, Thread.currentThread())
            accepted += item to result
          },
        )
      }
      windowFilled.await()
      assertEquals(2, peak.get())
      assertTrue(accepted.isEmpty())
      assertNull(withTimeoutOrNull(250) { beyondWindowStarted.await() })
      releaseFirst.complete(Unit)
      scan.await()
      assertEquals((0..9).map { it to if (it == 5) null else it * 2 }, accepted)
      assertEquals(0, active.get())
    }
  }

  fun testReadFailureCancelsAndJoinsOtherWorkers() = runBlocking {
    withTimeout(10_000) {
      val otherStarted = CompletableDeferred<Unit>()
      val otherStopped = CompletableDeferred<Unit>()
      val failure = IllegalStateException("Read failed")
      try {
        scanInOrder(
          items = listOf(0, 1),
          parallelism = 2,
          read = { item ->
            if (item == 0) {
              otherStarted.await()
              throw failure
            }
            try {
              otherStarted.complete(Unit)
              awaitCancellation()
            } finally {
              otherStopped.complete(Unit)
            }
          },
          accept = { _, _ -> fail("A failed read cannot be accepted") },
        )
        fail("Expected read failure")
      } catch (actual: IllegalStateException) {
        assertOriginalFailure(failure, actual)
        assertTrue(otherStopped.isCompleted)
      }
    }
  }

  fun testReadCancellationStopsThePool() = runBlocking {
    withTimeout(10_000) {
      val otherStarted = CompletableDeferred<Unit>()
      val otherStopped = CompletableDeferred<Unit>()
      val cancellation = CancellationException("Read superseded")
      try {
        scanInOrder(
          items = listOf(0, 1),
          parallelism = 2,
          read = { item ->
            if (item == 0) {
              otherStarted.await()
              throw cancellation
            }
            try {
              otherStarted.complete(Unit)
              awaitCancellation()
            } finally {
              otherStopped.complete(Unit)
            }
          },
          accept = { _, _ -> fail("A canceled read cannot be accepted") },
        )
        fail("Expected read cancellation")
      } catch (actual: CancellationException) {
        assertOriginalFailure(cancellation, actual)
        assertTrue(otherStopped.isCompleted)
      }
    }
  }

  fun testCollectorFailureCancelsAndJoinsWorkers() = runBlocking {
    withTimeout(10_000) {
      val otherStarted = CompletableDeferred<Unit>()
      val otherStopped = CompletableDeferred<Unit>()
      val failure = IllegalStateException("Conflicting result")
      try {
        scanInOrder(
          items = listOf(0, 1),
          parallelism = 2,
          read = { item ->
            if (item == 0) {
              otherStarted.await()
              item
            } else {
              try {
                otherStarted.complete(Unit)
                awaitCancellation()
              } finally {
                otherStopped.complete(Unit)
              }
            }
          },
          accept = { _, _ -> throw failure },
        )
        fail("Expected collector failure")
      } catch (actual: IllegalStateException) {
        assertOriginalFailure(failure, actual)
        assertTrue(otherStopped.isCompleted)
      }
    }
  }

  fun testParentCancellationWaitsForWorkerCleanup() = runBlocking {
    withTimeout(10_000) {
      val bothStarted = CompletableDeferred<Unit>()
      val cleanupStarted = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val active = AtomicInteger()
      val scan = launch {
        scanInOrder(
          items = (0..9).toList(),
          parallelism = 2,
          read = {
            if (active.incrementAndGet() == 2) bothStarted.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
                active.decrementAndGet()
              }
            }
          },
          accept = { _, _ -> fail("A canceled scan cannot accept pending reads") },
        )
      }
      try {
        bothStarted.await()
        scan.cancel()
        cleanupStarted.await()
        assertFalse(scan.isCompleted)
      } finally {
        releaseCleanup.complete(Unit)
        scan.cancelAndJoin()
      }
      assertEquals(0, active.get())
    }
  }

  fun testEmptyInputSkipsBothCallbacks() = runBlocking {
    scanInOrder(
      items = emptyList<Int>(),
      parallelism = 2,
      read = { fail("No read expected") },
      accept = { _, _ -> fail("No result expected") },
    )
  }

  /** Coroutine stack recovery can wrap the same failure while retaining its original cause. */
  private fun assertOriginalFailure(expected: Throwable, actual: Throwable) {
    assertEquals(expected.javaClass, actual.javaClass)
    assertEquals(expected.message, actual.message)
    var cause: Throwable? = actual
    while (cause != null) {
      if (cause === expected) return
      cause = cause.cause
    }
    fail("Expected the original failure in the cause chain")
  }
}
