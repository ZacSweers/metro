// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendProvider
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** These tests are only possible to run in jvm and native. */
@OptIn(ExperimentalAtomicApi::class)
class SuspendDoubleCheckConcurrentTest {
  // Use runBlocking and not runTest because we actually want multithreading in this test
  @Test
  fun get() = runBlocking {
    val numCoroutines = 10

    val mutex = Mutex(locked = true) // Start locked
    val provisions = AtomicInt(0)
    val provider =
      SuspendDoubleCheck.provider(
        SuspendProvider {
          // Wait until mutex is unlocked
          mutex.withLock {}
          provisions.incrementAndFetch()
          Any()
        }
      )

    val results = List(numCoroutines) { async(Dispatchers.Default) { provider() } }

    // Release all coroutines at once and await the results
    mutex.unlock()
    val values = results.awaitAll().toSet()

    assertEquals(1, provisions.load())
    assertEquals(1, values.size)
  }

  // Use runBlocking and not runTest because we actually want multithreading in this test
  @Test
  fun `racing callers all observe an initializer failure and a later call succeeds`() =
    runBlocking {
      val numCoroutines = 10

      val mutex = Mutex(locked = true) // Start locked
      val allowSuccess = AtomicInt(0)
      val attempts = AtomicInt(0)
      val provider =
        SuspendDoubleCheck.provider(
          SuspendProvider {
            // Wait until mutex is unlocked
            mutex.withLock {}
            attempts.incrementAndFetch()
            if (allowSuccess.load() == 0) {
              throw IllegalStateException("initializer fails")
            }
            Any()
          }
        )

      val results =
        List(numCoroutines) { async(Dispatchers.Default) { runCatching { provider() } } }

      // Release all coroutines at once and await the results
      mutex.unlock()
      val outcomes = results.awaitAll()

      // A failed initialization is not cached, so each caller retries and each observes the
      // failure.
      assertTrue(outcomes.all { it.isFailure }, "Expected all callers to fail, was $outcomes")

      // A later call, once the initializer can succeed, initializes normally.
      allowSuccess.store(1)
      provider()
      assertTrue((provider as SuspendDoubleCheck<*>).isInitialized())
    }
}
