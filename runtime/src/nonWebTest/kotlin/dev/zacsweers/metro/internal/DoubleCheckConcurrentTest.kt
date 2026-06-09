// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** These tests are only possible to run in jvm and native. */
@OptIn(ExperimentalAtomicApi::class)
class DoubleCheckConcurrentTest {
  // Use runBlocking and not runTest because we actually want multithreading in this test
  @Test
  fun get() = runBlocking {
    val numCoroutines = 10

    val mutex = Mutex(locked = true) // Start locked
    val provider = CoroutineLatchedProvider(mutex)
    val lazy = DoubleCheck.lazy(provider)

    val results = List(numCoroutines) { async(Dispatchers.Default) { lazy.value } }

    // Release all coroutines at once and await the results
    mutex.unlock()
    val values = results.awaitAll().toSet()

    assertEquals(1, provider.provisions.load())
    assertEquals(1, values.size)
  }

  /**
   * The sharpest edge of the init guard: a provider failure must release the guard and wake any
   * parked waiters so that one of them can retry initialization.
   */
  @Test
  fun providerExceptionReleasesGuardAndWakesWaiters() = runBlocking {
    val numCoroutines = 10

    val mutex = Mutex(locked = true) // Start locked
    val attempts = AtomicInt(0)
    val winner = Any()
    val provider = Provider {
      runBlocking { mutex.withLock {} }
      // Only the first thread through the guard fails. Entries are serialized by the guard, so
      // exactly one retry should succeed and everyone else should observe its value.
      if (attempts.incrementAndFetch() == 1) {
        throw UnsupportedOperationException("first init fails")
      }
      winner
    }
    val lazy = DoubleCheck.lazy(provider)

    val results =
      List(numCoroutines) {
        async(Dispatchers.Default) {
          try {
            lazy.value
          } catch (e: UnsupportedOperationException) {
            // The unlucky first initializer retries and must see the winning value
            lazy.value
          }
        }
      }

    mutex.unlock()
    val values = results.awaitAll().toSet()

    assertEquals(1, values.size)
    assertSame(winner, values.single())
    assertEquals(2, attempts.load())
  }

  /** Two independent guards initializing concurrently must not interfere via the shared parker. */
  @Test
  fun independentDoubleChecksDoNotInterfere() = runBlocking {
    val numCoroutines = 20

    val mutex = Mutex(locked = true) // Start locked
    val provider1 = CoroutineLatchedProvider(mutex)
    val provider2 = CoroutineLatchedProvider(mutex)
    val lazy1 = DoubleCheck.lazy(provider1)
    val lazy2 = DoubleCheck.lazy(provider2)

    val results =
      List(numCoroutines) { i ->
        async(Dispatchers.Default) { if (i % 2 == 0) lazy1.value else lazy2.value }
      }

    mutex.unlock()
    val values = results.awaitAll().toSet()

    assertEquals(1, provider1.provisions.load())
    assertEquals(1, provider2.provisions.load())
    assertEquals(2, values.size)
  }

  class CoroutineLatchedProvider(private val mutex: Mutex) : Provider<Any> {
    val provisions = AtomicInt(0)

    override fun invoke(): Any {
      runBlocking {
        // Wait until mutex is unlocked
        mutex.withLock {}
      }
      provisions.incrementAndFetch()
      return Any()
    }
  }
}
