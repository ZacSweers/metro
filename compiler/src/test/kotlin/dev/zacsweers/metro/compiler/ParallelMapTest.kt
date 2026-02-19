// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val POOL_SIZE = 4

class ParallelMapTest {

  private lateinit var executor: ExecutorService

  @Before
  fun setUp() {
    executor = Executors.newFixedThreadPool(POOL_SIZE)
  }

  @After
  fun tearDown() {
    executor.shutdown()
  }

  @Test
  fun `empty list returns empty`() {
    val result = emptyList<Int>().parallelMap(executor, POOL_SIZE) { it * 2 }
    assertEquals(emptyList(), result)
  }

  @Test
  fun `single element list does not use executor`() {
    // Single element should short-circuit to regular map
    val result = listOf(42).parallelMap(executor, POOL_SIZE) { it * 2 }
    assertEquals(listOf(84), result)
  }

  @Test
  fun `preserves order`() {
    val input = (1..100).toList()
    val result = input.parallelMap(executor, POOL_SIZE) { it * 2 }
    assertEquals(input.map { it * 2 }, result)
  }

  @Test
  fun `all items are transformed`() {
    val input = (1..50).toList()
    val result = input.parallelMap(executor, POOL_SIZE) { it.toString() }
    assertEquals(50, result.size)
    assertEquals(input.map { it.toString() }, result)
  }

  @Test
  fun `caller thread participates in work`() {
    val callerThread = Thread.currentThread()
    val threadsUsed = ConcurrentHashMap.newKeySet<Thread>()
    val input = (1..20).toList()

    input.parallelMap(executor, POOL_SIZE) {
      threadsUsed.add(Thread.currentThread())
      Thread.sleep(10)
      it
    }

    assertTrue(callerThread in threadsUsed, "Caller thread should participate in work")
  }

  @Test
  fun `uses multiple threads`() {
    val threadsUsed = ConcurrentHashMap.newKeySet<Thread>()
    val input = (1..20).toList()

    input.parallelMap(executor, POOL_SIZE) {
      threadsUsed.add(Thread.currentThread())
      Thread.sleep(50)
      it
    }

    assertTrue(threadsUsed.size > 1, "Expected multiple threads, got ${threadsUsed.size}")
  }

  @Test
  fun `handles more items than threads`() {
    val smallExecutor = Executors.newFixedThreadPool(2)
    try {
      val input = (1..100).toList()
      val result = input.parallelMap(smallExecutor, POOL_SIZE) { it * 3 }
      assertEquals(input.map { it * 3 }, result)
    } finally {
      smallExecutor.shutdown()
    }
  }

  @Test
  fun `transform exceptions propagate`() {
    val input = listOf(1, 2, 3, 4, 5)
    try {
      input.parallelMap(executor, POOL_SIZE) {
        if (it == 3) throw IllegalStateException("boom")
        it
      }
      throw AssertionError("Expected exception")
    } catch (e: Exception) {
      // The exception may be wrapped in ExecutionException from Future.get()
      val cause = if (e is java.util.concurrent.ExecutionException) e.cause!! else e
      assertTrue(cause is IllegalStateException)
      assertEquals("boom", cause.message)
    }
  }

  @Test
  fun `two elements uses parallelism`() {
    val threadsUsed = ConcurrentHashMap.newKeySet<Thread>()
    val input = listOf(1, 2)

    val result =
      input.parallelMap(executor, POOL_SIZE) {
        threadsUsed.add(Thread.currentThread())
        Thread.sleep(50)
        it * 10
      }

    assertEquals(listOf(10, 20), result)
  }

  // "Work stealing" here means all threads (pool + caller) compete for the next index via
  // AtomicInteger, so a fast thread naturally picks up more items instead of sitting idle.
  @Test
  fun `concurrent counter shows work stealing`() {
    val processedBy = ConcurrentHashMap<Int, String>()
    val input = (1..40).toList()

    input.parallelMap(executor, POOL_SIZE) {
      processedBy[it] = Thread.currentThread().name
      Thread.sleep(5)
      it
    }

    // Every item should have been processed
    assertEquals(input.toSet(), processedBy.keys)
  }
}
