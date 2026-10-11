// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val POOL_SIZE = 4

class ParallelMapTest {

  private lateinit var pool: ForkJoinPool

  @Before
  fun setUp() {
    pool = ForkJoinPool(POOL_SIZE)
  }

  @After
  fun tearDown() {
    pool.shutdown()
  }

  @Test
  fun `empty list returns empty`() {
    val result = emptyList<Int>().parallelMap(pool) { it * 2 }
    assertEquals(emptyList(), result)
  }

  @Test
  fun `single element list does not use pool`() {
    // Single element should short-circuit to regular map
    val result = listOf(42).parallelMap(pool) { it * 2 }
    assertEquals(listOf(84), result)
  }

  @Test
  fun `preserves order`() {
    val input = (1..100).toList()
    val result = input.parallelMap(pool) { it * 2 }
    assertEquals(input.map { it * 2 }, result)
  }

  @Test
  fun `all items are transformed`() {
    val input = (1..50).toList()
    val result = input.parallelMap(pool) { it.toString() }
    assertEquals(50, result.size)
    assertEquals(input.map { it.toString() }, result)
  }

  @Test
  fun `uses multiple threads`() {
    val threadsUsed = ConcurrentHashMap.newKeySet<Thread>()
    val input = (1..20).toList()

    val _ =
      input.parallelMap(pool) {
        threadsUsed.add(Thread.currentThread())
        Thread.sleep(50)
        it
      }

    assertTrue(threadsUsed.size > 1, "Expected multiple threads, got ${threadsUsed.size}")
  }

  @Test
  fun `handles more items than threads`() {
    val smallPool = ForkJoinPool(2)
    try {
      val input = (1..100).toList()
      val result = input.parallelMap(smallPool) { it * 3 }
      assertEquals(input.map { it * 3 }, result)
    } finally {
      smallPool.shutdown()
    }
  }

  @Test
  fun `transform exceptions propagate`() {
    val input = listOf(1, 2, 3, 4, 5)
    try {
      val _ =
        input.parallelMap(pool) {
          if (it == 3) {
            throw IllegalStateException("boom")
          }
          it
        }
      throw AssertionError("Expected exception")
    } catch (e: Exception) {
      val cause = if (e.cause is IllegalStateException) e.cause!! else e
      assertTrue(cause is IllegalStateException)
      assertEquals("boom", cause.message)
    }
  }

  @Test
  fun `failures wait for remaining work and retain later failures`() {
    val caller = Executors.newSingleThreadExecutor()
    val secondStarted = CountDownLatch(1)
    val firstFailed = CountDownLatch(1)
    val finishSecond = CountDownLatch(1)
    val secondFinished = CountDownLatch(1)
    val firstFailure = IllegalStateException("first task failed")
    val secondFailure = AssertionError("second task failed")
    try {
      val result =
        caller.submit<IllegalStateException> {
          assertFailsWith<IllegalStateException> {
            listOf(1, 2).parallelMap(pool) { item ->
              if (item == 1) {
                assertTrue(secondStarted.await(5, SECONDS))
                firstFailed.countDown()
                throw firstFailure
              }
              secondStarted.countDown()
              assertTrue(finishSecond.await(5, SECONDS))
              secondFinished.countDown()
              throw secondFailure
            }
          }
        }

      assertTrue(firstFailed.await(5, SECONDS))
      // Hold the second task open while checking that the caller is still waiting.
      assertFailsWith<TimeoutException> { result.get(100, MILLISECONDS) }
      finishSecond.countDown()

      val failure = result.get(5, SECONDS)
      assertEquals(0L, secondFinished.count)
      assertTrue(failure === firstFailure || failure.cause === firstFailure)
      val suppressed = failure.suppressed.single()
      assertTrue(suppressed === secondFailure || suppressed.cause === secondFailure)
    } finally {
      finishSecond.countDown()
      caller.shutdownNow()
    }
  }

  @Test
  fun `two elements uses parallelism`() {
    val threadsUsed = ConcurrentHashMap.newKeySet<Thread>()
    val input = listOf(1, 2)

    val result =
      input.parallelMap(pool) {
        threadsUsed.add(Thread.currentThread())
        Thread.sleep(50)
        it * 10
      }

    assertEquals(listOf(10, 20), result)
  }

  @Test
  fun `work stealing distributes across threads`() {
    val processedBy = ConcurrentHashMap<Int, String>()
    val input = (1..40).toList()

    val _ =
      input.parallelMap(pool) {
        processedBy[it] = Thread.currentThread().name
        Thread.sleep(5)
        it
      }

    // Every item should have been processed
    assertEquals(input.toSet(), processedBy.keys)
  }

  @Test
  fun `nested parallelMap does not deadlock`() {
    // Simulates the recursive graph extension validation pattern:
    // outer parallelMap processes 3 items, each of which does an inner parallelMap of 2 items.
    // With a fixed thread pool this would deadlock; with ForkJoinPool work-stealing it works.
    val input = listOf("a", "b", "c")

    val result =
      input.parallelMap(pool) { outer ->
        val inner = listOf(1, 2)
        val innerResults =
          inner.parallelMap(pool) { num ->
            Thread.sleep(10)
            "$outer$num"
          }
        innerResults.joinToString(",")
      }

    assertEquals(listOf("a1,a2", "b1,b2", "c1,c2"), result)
  }
}
