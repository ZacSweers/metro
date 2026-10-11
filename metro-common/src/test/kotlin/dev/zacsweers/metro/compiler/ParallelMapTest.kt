// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exercises overlap, ordering, cancellation, and cleanup without any real reads. */
class ParallelMapTest {
  @Test
  fun `one worker reads and accepts on the caller`() = runBlocking {
    val caller = Thread.currentThread()
    val events = mutableListOf<String>()
    val _ =
      listOf(1, 2, 3)
        .parallelMap(
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

  @Test
  fun `one worker skips accept when read cancels the caller`() = runBlocking {
    val reads = mutableListOf<Int>()
    val accepted = mutableListOf<Int>()
    val scan = launch {
      val job = coroutineContext.job
      val _ =
        listOf(1, 2)
          .parallelMap(
            parallelism = 1,
            read = { item ->
              reads += item
              job.cancel()
              item
            },
            accept = { item, _ -> accepted += item },
          )
    }
    scan.join()
    assertTrue(scan.isCancelled)
    assertEquals(listOf(1), reads)
    assertTrue(accepted.isEmpty())
  }

  @Test
  fun `one worker stops reading when accept cancels the caller`() = runBlocking {
    val reads = mutableListOf<Int>()
    val accepted = mutableListOf<Int>()
    val scan = launch {
      val job = coroutineContext.job
      val _ =
        listOf(1, 2)
          .parallelMap(
            parallelism = 1,
            read = { item ->
              reads += item
              item
            },
            accept = { item, _ ->
              accepted += item
              job.cancel()
            },
          )
    }
    scan.join()
    assertTrue(scan.isCancelled)
    assertEquals(listOf(1), reads)
    assertEquals(listOf(1), accepted)
  }

  @Test
  fun `pooled reads run concurrently off the caller thread`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val caller = Thread.currentThread()
      val readThreads = ConcurrentHashMap.newKeySet<Thread>()
      val rendezvous = CyclicBarrier(2)
      val accepted = mutableListOf<Pair<Int, Int>>()
      val _ =
        listOf(1, 2)
          .parallelMap(
            parallelism = 2,
            read = { item ->
              readThreads += Thread.currentThread()
              // Both reads must block here at the same time, which needs two threads.
              rendezvous.await(5, TimeUnit.SECONDS)
              item * 2
            },
            accept = { item, result ->
              assertSame(caller, Thread.currentThread())
              accepted += item to result
            },
          )
      assertFalse(readThreads.contains(caller))
      assertEquals(2, readThreads.size)
      assertEquals(listOf(1 to 2, 2 to 4), accepted)
    }
  }

  @Test
  fun `pooled single item reads off the caller and accepts once`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val caller = Thread.currentThread()
      val accepted = mutableListOf<Pair<Int, Int>>()
      val _ =
        listOf(42)
          .parallelMap(
            parallelism = 8,
            read = { item ->
              assertNotSame(caller, Thread.currentThread())
              item * 2
            },
            accept = { item, result ->
              assertSame(caller, Thread.currentThread())
              accepted += item to result
            },
          )
      assertEquals(listOf(42 to 84), accepted)
    }
  }

  @Test
  fun `pool larger than input reads every item at once`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val started = AtomicInteger()
      val allStarted = CompletableDeferred<Unit>()
      val accepted = mutableListOf<Pair<Int, Int>>()
      val _ =
        listOf(1, 2, 3)
          .parallelMap(
            parallelism = 8,
            read = { item ->
              if (started.incrementAndGet() == 3) {
                allStarted.complete(Unit)
              }
              // Every read waits for the others, so fewer than three workers would hang here.
              allStarted.await()
              item * 2
            },
            accept = { item, result -> accepted += item to result },
          )
      assertEquals(listOf(1 to 2, 2 to 4, 3 to 6), accepted)
    }
  }

  @Test
  fun `duplicate items are read and accepted per index`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val reads = AtomicInteger()
      val accepted = mutableListOf<Pair<String, Int>>()
      val _ =
        listOf("a", "a", "b")
          .parallelMap(
            parallelism = 2,
            read = { reads.incrementAndGet() },
            accept = { item, result -> accepted += item to result },
          )
      assertEquals(listOf("a", "a", "b"), accepted.map { it.first })
      assertEquals(listOf(1, 2, 3), accepted.map { it.second }.sorted())
    }
  }

  @Test
  fun `rejects non-positive parallelism`() = runBlocking {
    for (parallelism in listOf(0, -1)) {
      try {
        val _ =
          listOf(1)
            .parallelMap(
              parallelism,
              read = { fail("No read expected") },
              accept = { _, _ -> fail("No accept expected") },
            )
        fail("Expected $parallelism to be rejected")
      } catch (expected: IllegalArgumentException) {
        assertTrue(expected.message!!.contains(parallelism.toString()))
      }
    }
  }

  @Test
  fun `reads run in the supplied context`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val caller = Thread.currentThread()
      val readThreads = ConcurrentHashMap.newKeySet<Thread>()
      val accepted = mutableListOf<Int>()
      val executor = Executors.newSingleThreadExecutor()
      val worker = executor.submit(Callable { Thread.currentThread() }).get()
      executor.asCoroutineDispatcher().use { dispatcher ->
        listOf(1, 2, 3)
          .parallelMap(
            parallelism = 2,
            context = dispatcher,
            read = { item ->
              readThreads += Thread.currentThread()
              item
            },
            accept = { item, _ ->
              assertSame(caller, Thread.currentThread())
              accepted += item
            },
          )
      }
      assertEquals(setOf(worker), readThreads.toSet())
      assertEquals(listOf(1, 2, 3), accepted)
    }
  }

  @Test
  fun `rejects a context with a Job`() = runBlocking {
    try {
      val _ =
        listOf(1)
          .parallelMap(
            parallelism = 2,
            context = Job(),
            read = { fail("No read expected") },
            accept = { _, _ -> fail("No accept expected") },
          )
      fail("Expected the Job to be rejected")
    } catch (expected: IllegalArgumentException) {
      assertEquals("context must not contain a Job", expected.message)
    }
  }

  @Test
  fun `parallel reads have a bounded backlog and accept in order on the caller`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val caller = Thread.currentThread()
      val firstStarted = CompletableDeferred<Unit>()
      val releaseFirst = CompletableDeferred<Unit>()
      val windowFilled = CompletableDeferred<Unit>()
      val acceptedCount = AtomicInteger()
      val acceptedWhenBeyondWindowStarted = AtomicInteger(-1)
      val active = AtomicInteger()
      val peak = AtomicInteger()
      val accepted = mutableListOf<Pair<Int, Int?>>()
      val scan = async {
        (0..9)
          .toList()
          .parallelMap(
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
                if (item == 3) {
                  windowFilled.complete(Unit)
                }
                if (item == 4) {
                  // Item 4 needs a permit back, so item 0 must have been accepted by now.
                  acceptedWhenBeyondWindowStarted.set(acceptedCount.get())
                }
                if (item == 5) {
                  null
                } else {
                  item * 2
                }
              } finally {
                active.decrementAndGet()
              }
            },
            accept = { item, result ->
              assertSame(caller, Thread.currentThread())
              accepted += item to result
              acceptedCount.incrementAndGet()
            },
          )
      }
      windowFilled.await()
      assertEquals(2, peak.get())
      assertTrue(accepted.isEmpty())
      releaseFirst.complete(Unit)
      scan.await()
      assertTrue(
        acceptedWhenBeyondWindowStarted.get() >= 1,
        "Item 4 started before anything was accepted",
      )
      assertEquals(
        (0..9).map {
          it to
            if (it == 5) {
              null
            } else {
              it * 2
            }
        },
        accepted,
      )
      assertEquals(0, active.get())
    }
  }

  @Test
  fun `plain map reads ahead past a slow item and returns in order`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val lastStarted = CompletableDeferred<Unit>()
      val results =
        (0..9).toList().parallelMap(parallelism = 2) { item ->
          // Item 0 can't finish until every later item has been read, which the in-flight
          // limit would never allow.
          when (item) {
            0 -> lastStarted.await()
            9 -> lastStarted.complete(Unit)
            else -> {}
          }
          item * 2
        }
      assertEquals((0..9).map { it * 2 }, results)
    }
  }

  @Test
  fun `read failure cancels and joins other workers`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val otherStarted = CompletableDeferred<Unit>()
      val otherStopped = CompletableDeferred<Unit>()
      val failure = IllegalStateException("Read failed")
      try {
        val _ =
          listOf(0, 1)
            .parallelMap(
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

  @Test
  fun `concurrent failures surface as one exception with the other suppressed`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val bothStarted = CountDownLatch(2)
      val first = IllegalStateException("first")
      val second = IllegalArgumentException("second")
      try {
        val _ =
          listOf(0, 1).parallelMap(parallelism = 2) { item ->
            // Block instead of suspending so neither throw can turn into a cancellation.
            bothStarted.countDown()
            bothStarted.await(5, TimeUnit.SECONDS)
            if (item == 0) {
              throw first
            } else {
              throw second
            }
          }
        fail("Expected both reads to fail")
      } catch (actual: RuntimeException) {
        val chain = generateSequence<Throwable>(actual) { it.cause }.toList()
        val thrown = listOf(first, second).single { it in chain }
        val other =
          if (thrown === first) {
            second
          } else {
            first
          }
        assertTrue(
          chain.any { other in it.suppressed },
          "Expected the sibling failure as suppressed",
        )
      }
    }
  }

  @Test
  fun `read cancellation stops the pool`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val otherStarted = CompletableDeferred<Unit>()
      val otherStopped = CompletableDeferred<Unit>()
      val cancellation = CancellationException("Read superseded")
      try {
        val _ =
          listOf(0, 1)
            .parallelMap(
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

  @Test
  fun `read failure keeps accepted results and drops the rest`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val secondAccepted = CompletableDeferred<Unit>()
      val lastStopped = CompletableDeferred<Unit>()
      val failure = IllegalStateException("Read failed")
      val accepted = mutableListOf<Int>()
      try {
        val _ =
          listOf(0, 1, 2, 3)
            .parallelMap(
              parallelism = 2,
              read = { item ->
                when (item) {
                  2 -> {
                    secondAccepted.await()
                    throw failure
                  }

                  3 ->
                    try {
                      awaitCancellation()
                    } finally {
                      lastStopped.complete(Unit)
                    }

                  else -> {}
                }
                item
              },
              accept = { item, _ ->
                accepted += item
                if (item == 1) {
                  secondAccepted.complete(Unit)
                }
              },
            )
        fail("Expected read failure")
      } catch (actual: IllegalStateException) {
        assertOriginalFailure(failure, actual)
        assertTrue(lastStopped.isCompleted)
        assertEquals(listOf(0, 1), accepted)
      }
    }
  }

  @Test
  fun `read cancellation still accepts earlier completed results`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val firstAccepted = CompletableDeferred<Unit>()
      val lastStarted = CompletableDeferred<Unit>()
      val lastStopped = CompletableDeferred<Unit>()
      val cancellation = CancellationException("Read superseded")
      val accepted = mutableListOf<Int>()
      try {
        val _ =
          listOf(0, 1, 2, 3)
            .parallelMap(
              parallelism = 2,
              read = { item ->
                when (item) {
                  // Item 1 is sent before its worker takes another item, so it's already in the
                  // results channel when item 2 fails.
                  1 -> firstAccepted.await()
                  2 -> {
                    lastStarted.await()
                    throw cancellation
                  }

                  3 ->
                    try {
                      lastStarted.complete(Unit)
                      awaitCancellation()
                    } finally {
                      lastStopped.complete(Unit)
                    }

                  else -> {}
                }
                item
              },
              accept = { item, _ ->
                accepted += item
                if (item == 0) {
                  firstAccepted.complete(Unit)
                }
              },
            )
        fail("Expected read cancellation")
      } catch (actual: CancellationException) {
        assertOriginalFailure(cancellation, actual)
        assertTrue(lastStopped.isCompleted)
        assertEquals(listOf(0, 1), accepted)
      }
    }
  }

  @Test
  fun `pooled collector skips pending results after cancellation`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val thirdReadStarted = CompletableDeferred<Unit>()
      val accepted = mutableListOf<Int>()
      val scan = launch {
        val job = coroutineContext.job
        val _ =
          listOf(0, 1, 2)
            .parallelMap(
              parallelism = 2,
              read = { item ->
                when (item) {
                  // The worker that read item 1 sent it before moving on to item 2.
                  0 -> thirdReadStarted.await()
                  2 -> {
                    thirdReadStarted.complete(Unit)
                    awaitCancellation()
                  }
                  else -> {}
                }
                item
              },
              accept = { item, _ ->
                accepted += item
                job.cancel()
              },
            )
      }
      scan.join()
      assertTrue(scan.isCancelled)
      assertEquals(listOf(0), accepted)
    }
  }

  @Test
  fun `pooled worker stops taking items after cancellation`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val secondReadStarted = CompletableDeferred<Unit>()
      val firstReadReturned = CompletableDeferred<Unit>()
      val reads = ConcurrentHashMap.newKeySet<Int>()
      val scan = launch {
        val job = coroutineContext.job
        val _ =
          listOf(0, 1, 2)
            .parallelMap(
              parallelism = 2,
              read = { item ->
                reads += item
                when (item) {
                  0 -> {
                    secondReadStarted.await()
                    job.cancel()
                    firstReadReturned.complete(Unit)
                  }
                  // Hold the other worker so item 2 stays queued until after the cancel.
                  1 -> {
                    secondReadStarted.complete(Unit)
                    withContext(NonCancellable) { firstReadReturned.await() }
                  }
                  else -> {}
                }
                item
              },
              accept = { _, _ -> fail("A canceled scan cannot accept") },
            )
      }
      scan.join()
      assertTrue(scan.isCancelled)
      assertEquals(setOf(0, 1), reads.toSet())
    }
  }

  @Test
  fun `collector failure cancels and joins workers`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val otherStarted = CompletableDeferred<Unit>()
      val otherStopped = CompletableDeferred<Unit>()
      val failure = IllegalStateException("Conflicting result")
      try {
        val _ =
          listOf(0, 1)
            .parallelMap(
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

  @Test
  fun `parent cancellation waits for worker cleanup`() = runBlocking {
    withTimeout(10_000.milliseconds) {
      val bothStarted = CompletableDeferred<Unit>()
      val cleanupStarted = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val active = AtomicInteger()
      val scan = launch {
        val _ =
          (0..9)
            .toList()
            .parallelMap(
              parallelism = 2,
              read = {
                if (active.incrementAndGet() == 2) {
                  bothStarted.complete(Unit)
                }
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

  @Test
  fun `indexed callbacks receive each item's index`() = runBlocking {
    for (parallelism in listOf(1, 2)) {
      val accepted = mutableListOf<Triple<Int, String, String>>()
      val _ =
        listOf("a", "b", "c")
          .parallelMapIndexed(
            parallelism,
            read = { index, item -> "$index:$item" },
            accept = { index, item, result -> accepted += Triple(index, item, result) },
          )
      assertEquals(
        listOf(Triple(0, "a", "0:a"), Triple(1, "b", "1:b"), Triple(2, "c", "2:c")),
        accepted,
      )
    }
  }

  @Test
  fun `empty input skips both callbacks`() = runBlocking {
    val results =
      emptyList<Int>()
        .parallelMap(
          parallelism = 2,
          read = { fail("No read expected") },
          accept = { _, _ -> fail("No result expected") },
        )
    assertTrue(results.isEmpty())
  }

  /** Coroutine stack recovery can wrap the same failure while retaining its original cause. */
  private fun assertOriginalFailure(expected: Throwable, actual: Throwable) {
    assertEquals(expected.javaClass, actual.javaClass)
    assertEquals(expected.message, actual.message)
    var cause: Throwable? = actual
    while (cause != null) {
      if (cause === expected) {
        return
      }
      cause = cause.cause
    }
    fail("Expected the original failure in the cause chain")
  }
}
