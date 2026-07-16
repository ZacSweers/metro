// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
class SuspendLazyTest {
  @Test fun `synchronized mode computes once`() = computesOnce(LazyThreadSafetyMode.SYNCHRONIZED)

  @Test
  fun `publication mode computes once when sequential`() =
    computesOnce(LazyThreadSafetyMode.PUBLICATION)

  @Test fun `none mode computes once when sequential`() = computesOnce(LazyThreadSafetyMode.NONE)

  @Test
  fun `suspendLazy supports nullable values`() = runTest {
    val count = AtomicInt(0)
    val lazy =
      suspendLazy<String?> {
        count.incrementAndFetch()
        null
      }

    assertFalse(lazy.isInitialized())
    assertNull(lazy.value())
    assertTrue(lazy.isInitialized())
    assertNull(lazy.value())
    assertEquals(1, count.load())
  }

  @Test
  fun `publication mode publishes one value when initializers overlap`() = runTest {
    val count = AtomicInt(0)
    val releaseInitializers = CompletableDeferred<Unit>()
    val lazy =
      suspendLazy(LazyThreadSafetyMode.PUBLICATION) {
        val value = count.incrementAndFetch()
        releaseInitializers.await()
        value
      }

    val first = async { lazy.value() }
    val second = async { lazy.value() }
    runCurrent()
    assertEquals(2, count.load())
    releaseInitializers.complete(Unit)

    assertEquals(first.await(), second.await())
    assertTrue(lazy.isInitialized())
  }

  @Test
  fun `publication mode retries after a failed initializer`() =
    retriesAfterFailure(LazyThreadSafetyMode.PUBLICATION)

  @Test
  fun `none mode retries after a failed initializer`() =
    retriesAfterFailure(LazyThreadSafetyMode.NONE)

  @Test
  fun `publication mode retries after a cancelled initializer`() =
    retriesAfterCancellation(LazyThreadSafetyMode.PUBLICATION)

  @Test
  fun `none mode retries after a cancelled initializer`() =
    retriesAfterCancellation(LazyThreadSafetyMode.NONE)

  private fun retriesAfterFailure(mode: LazyThreadSafetyMode) = runTest {
    val count = AtomicInt(0)
    val lazy =
      suspendLazy(mode) {
        if (count.incrementAndFetch() == 1) {
          throw IllegalStateException("first attempt fails")
        }
        "value"
      }

    assertFailsWith<IllegalStateException> { lazy.value() }
    // A failed initializer is not cached, so the next caller retries.
    assertFalse(lazy.isInitialized())
    assertEquals("value", lazy.value())
    assertTrue(lazy.isInitialized())
    assertEquals(2, count.load())
  }

  private fun retriesAfterCancellation(mode: LazyThreadSafetyMode) = runTest {
    val count = AtomicInt(0)
    val entered = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val lazy =
      suspendLazy(mode) {
        if (count.incrementAndFetch() == 1) {
          entered.complete(Unit)
          // Suspends until cancelled
          gate.await()
        }
        "value"
      }

    val job = launch { lazy.value() }
    entered.await()
    job.cancelAndJoin()

    // Cancellation mid-initialization leaves the lazy uninitialized, so the next caller recomputes.
    assertFalse(lazy.isInitialized())
    assertEquals("value", lazy.value())
    assertEquals(2, count.load())
  }

  private fun computesOnce(mode: LazyThreadSafetyMode) = runTest {
    val count = AtomicInt(0)
    val lazy =
      suspendLazy(mode) {
        count.incrementAndFetch()
        "value"
      }
    assertFalse(lazy.isInitialized())
    assertEquals("value", lazy.value())
    assertTrue(lazy.isInitialized())
    assertEquals("value", lazy.value())
    assertEquals(1, count.load())
  }
}
