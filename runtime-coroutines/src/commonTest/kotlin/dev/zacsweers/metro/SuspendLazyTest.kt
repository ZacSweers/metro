// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
