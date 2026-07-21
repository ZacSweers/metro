// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalAtomicApi::class)
class SuspendLazyPublicationConcurrentTest {
  // Blocking both initializers until they have entered requires separate dispatcher threads. This
  // exercises real contention on JVM and Native rather than scheduler interleaving.
  @Test
  fun `publication mode publishes one value under thread contention`() = runBlocking {
    val attempts = AtomicInt(0)
    val lazy =
      suspendLazy(LazyThreadSafetyMode.PUBLICATION) {
        val value = attempts.incrementAndFetch()
        val waitStarted = TimeSource.Monotonic.markNow()
        while (attempts.load() < 2) {
          check(waitStarted.elapsedNow() < 10.seconds) {
            "Timed out waiting for overlapping initializer"
          }
        }
        value
      }

    val results = List(2) { async(Dispatchers.Default) { lazy.value() } }.awaitAll()

    assertEquals(2, attempts.load())
    assertEquals(1, results.toSet().size)
    assertEquals(results.first(), lazy.value())
    assertTrue(lazy.isInitialized())
  }
}
