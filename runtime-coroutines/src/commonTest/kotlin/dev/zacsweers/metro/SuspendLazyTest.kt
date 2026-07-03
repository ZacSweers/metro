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
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalAtomicApi::class)
class SuspendLazyTest {
  @Test fun `synchronized mode computes once`() = computesOnce(LazyThreadSafetyMode.SYNCHRONIZED)

  @Test
  fun `publication mode computes once when sequential`() =
    computesOnce(LazyThreadSafetyMode.PUBLICATION)

  @Test fun `none mode computes once when sequential`() = computesOnce(LazyThreadSafetyMode.NONE)

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

  @Test
  fun `suspendLazyOf is already initialized`() = runTest {
    val lazy = suspendLazyOf("value")
    assertTrue(lazy.isInitialized())
    assertEquals("value", lazy.value())
  }
}
