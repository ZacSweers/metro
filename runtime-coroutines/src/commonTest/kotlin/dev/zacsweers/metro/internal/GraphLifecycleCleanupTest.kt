// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GraphLifecycleCleanupTest {
  @OptIn(ExperimentalMetroApi::class)
  @Test
  fun aSuspendedChildFailureStopsParentCleanup() = runTest {
    val state = GraphLifecycleState()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    state.register(
      object : SuspendCloseableGraph {
        override suspend fun close() {
          entered.complete(Unit)
          release.await()
          error("child failed")
        }
      },
      GraphLifecycleState(),
    )
    var calls = 0
    val caller = launch {
      val failure =
        assertFailsWith<IllegalStateException> {
          closeSuspendGraph(state, state, null) { children ->
            closeSuspendChildren(children)
            calls++
          }
        }
      assertEquals("child failed", failure.message)
    }
    entered.await()
    caller.cancel()
    closeSuspendGraph(state, state, null) { calls++ }
    release.complete(Unit)
    caller.join()
    assertEquals(0, calls)
  }

  @Test
  fun concurrentCloseReturnsAndCancellationAllowsCleanupToFinish() = runTest {
    val state = GraphLifecycleState()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var finished = false
    val caller = launch {
      closeSuspendGraph(state, state, null) {
        entered.complete(Unit)
        release.await()
        finished = true
      }
    }
    entered.await()
    caller.cancel()
    closeSuspendGraph(state, state, null) { error("second cleanup") }
    release.complete(Unit)
    caller.join()
    assertTrue(finished)
  }
}
