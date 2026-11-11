// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroApi::class, ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class GraphCleanupTest {
  @Test
  fun cancellingTheCallerLetsCleanupFinish() = runTest {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var finished = false
    val caller = launch {
      runGraphCleanup {
        entered.complete(Unit)
        release.await()
        finished = true
      }
    }
    entered.await()
    caller.cancel()
    release.complete(Unit)
    caller.join()
    assertTrue(finished)
  }

  @Test
  fun anAlreadyCancelledCallerStillRunsCleanup() = runTest {
    var finished = false
    val caller = launch {
      cancel()
      runGraphCleanup { finished = true }
    }
    caller.join()
    assertTrue(caller.isCancelled)
    assertTrue(finished)
  }

  @Test
  fun aFailureReachesTheCaller() = runTest {
    val failure = assertFailsWith<IllegalStateException> { runGraphCleanup { error("failed") } }
    assertEquals("failed", failure.message)
  }

  @Test
  fun closingChildrenAwaitsTheOnesThatSuspend() = runTest {
    val closed = mutableSetOf<String>()
    val state = GraphLifecycleState()
    val blocking =
      object : AutoCloseable {
        override fun close() {
          closed += "blocking"
        }
      }
    val suspending =
      object : SuspendCloseableGraph {
        override suspend fun close() {
          yield()
          closed += "suspending"
        }
      }
    state.addChild(blocking, GraphLifecycleState())
    state.addChild(suspending, GraphLifecycleState())
    closeGraphChildren(state)
    assertEquals(setOf("blocking", "suspending"), closed)
    assertEquals(emptyList(), state.takeChildren())
  }
}
