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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalAtomicApi::class)
class SuspendProviderMemoizeTest {
  @Test
  fun `memoize computes once`() = runTest {
    val count = AtomicInt(0)
    val memoized = SuspendProvider {
      count.incrementAndFetch()
      Any()
    }
      .memoize()

    val first = memoized()
    assertSame(first, memoized())
    assertEquals(1, count.load())
  }

  @Test
  fun `memoize supports nullable values`() = runTest {
    val count = AtomicInt(0)
    val memoized =
      SuspendProvider<String?> {
          count.incrementAndFetch()
          null
        }
        .memoize()

    assertNull(memoized())
    assertNull(memoized())
    assertEquals(1, count.load())
  }

  @Test
  fun `memoizeAsLazy computes once`() = runTest {
    val count = AtomicInt(0)
    val lazy = SuspendProvider {
      count.incrementAndFetch()
      Any()
    }
      .memoizeAsLazy()

    assertFalse(lazy.isInitialized())
    val first = lazy.value()
    assertSame(first, lazy.value())
    assertTrue(lazy.isInitialized())
    assertEquals(1, count.load())
  }

  @Test
  fun `memoizeAsLazy supports nullable values`() = runTest {
    val count = AtomicInt(0)
    val lazy =
      SuspendProvider<String?> {
          count.incrementAndFetch()
          null
        }
        .memoizeAsLazy()

    assertNull(lazy.value())
    assertNull(lazy.value())
    assertEquals(1, count.load())
  }
}
