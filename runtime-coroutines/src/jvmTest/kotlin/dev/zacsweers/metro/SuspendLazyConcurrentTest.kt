// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class SuspendLazyConcurrentTest {
  @Test
  fun `publication mode publishes one value when initializers overlap on different threads`() =
    runBlocking {
      val count = AtomicInteger(0)
      val initializersEntered = CountDownLatch(2)
      val releaseInitializers = CountDownLatch(1)
      val lazy =
        suspendLazy(LazyThreadSafetyMode.PUBLICATION) {
          val value = count.incrementAndGet()
          initializersEntered.countDown()
          check(releaseInitializers.await(10, TimeUnit.SECONDS)) {
            "Timed out waiting to release initializers"
          }
          value
        }

      val first = async(Dispatchers.Default) { lazy.value() }
      val second = async(Dispatchers.Default) { lazy.value() }
      try {
        assertTrue(
          initializersEntered.await(10, TimeUnit.SECONDS),
          "Initializers did not overlap",
        )
        assertEquals(2, count.get())
      } finally {
        releaseInitializers.countDown()
      }

      val published = first.await()
      assertEquals(published, second.await())
      assertEquals(published, lazy.value())
      assertTrue(lazy.isInitialized())
    }
}
