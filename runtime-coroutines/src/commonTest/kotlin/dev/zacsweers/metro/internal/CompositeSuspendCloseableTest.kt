// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class CompositeSuspendCloseableTest {
  @Test
  fun cancellationDoesNotInterruptCleanup() = runTest {
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    val composite = CompositeSuspendCloseable()
    var calls = 0
    composite.add(Any()) {
      entered.complete(Unit)
      resume.await()
      calls++
    }
    val cleanup = launch { runGraphClose { composite.close() } }
    entered.await()
    cleanup.cancel()
    yield()
    resume.complete(Unit)
    cleanup.cancelAndJoin()
    composite.close()
    assertEquals(1, calls)
  }

  @Test
  fun failureStopsCleanupAndReleasesRemainingCallbacks() = runTest {
    val composite = CompositeSuspendCloseable()
    var calls = 0
    composite.add(Any()) { error("failure") }
    composite.add(Any()) { calls++ }
    assertFailsWith<IllegalStateException> { composite.close() }
    composite.close()
    assertEquals(0, calls)
  }
}
