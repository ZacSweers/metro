// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalAtomicApi::class)
class SuspendProviderExtensionsTest {
  @Test
  fun `map transforms lazily`() = runTest {
    val count = AtomicInt(0)
    val mapped = SuspendProvider {
      count.incrementAndFetch()
      21
    }
      .map { it * 2 }
    assertEquals(0, count.load())
    assertEquals(42, mapped())
    assertEquals(42, mapped())
    // Not memoized, underlying provider re-invokes
    assertEquals(2, count.load())
  }

  @Test
  fun `flatMap flattens`() = runTest {
    val provider = SuspendProvider { 21 }.flatMap { value -> SuspendProvider { value * 2 } }
    assertEquals(42, provider())
  }

  @Test
  fun `zip combines`() = runTest {
    val zipped = SuspendProvider { 40 }.zip(SuspendProvider { 2 }) { a, b -> a + b }
    assertEquals(42, zipped())
  }
}
