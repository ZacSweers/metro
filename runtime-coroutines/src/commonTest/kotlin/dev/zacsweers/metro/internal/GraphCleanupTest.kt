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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

  @Test
  fun aFailingChildDoesNotStopTheOthers() = runTest {
    var closes = 0
    val state = GraphLifecycleState()
    val failing =
      object : SuspendCloseableGraph {
        override suspend fun close() {
          closes++
          error("failed")
        }
      }
    val other =
      object : AutoCloseable {
        override fun close() {
          closes++
        }
      }
    state.addChild(failing, GraphLifecycleState())
    state.addChild(other, GraphLifecycleState())
    closeGraphChildren(state)
    assertEquals(2, closes)
    val failure = assertFailsWith<IllegalStateException> { state.throwCloseFailure() }
    assertEquals("failed", failure.message)
  }

  @Test
  fun aSuspendChildRegisteredAfterCloseIsClosed() = runTest {
    val parent = GraphLifecycleState()
    parent.markClosed()
    var closes = 0
    val child =
      object : SuspendCloseableGraph {
        override suspend fun close() {
          yield()
          closes++
        }
      }
    assertFailsWith<IllegalStateException> {
      addSuspendCloseableChild(parent, child, GraphLifecycleState())
    }
    assertEquals(1, closes)
    assertEquals(emptyList(), parent.takeChildren())
  }

  @Test
  fun closingChildrenAwaitsAChildClosingElsewhere() =
    runTest(timeout = 5.seconds) {
      val events = mutableListOf<String>()
      val parent = GraphLifecycleState()
      val release = CompletableDeferred<Unit>()
      val child = SuspendingChild(parent) { release.await() }
      val childClose = launch { child.close() }
      runCurrent()
      val parentClose = launch {
        closeGraphChildren(parent)
        events += "parent closed its children"
      }
      runCurrent()
      assertFalse(parentClose.isCompleted)
      events += "child released"
      release.complete(Unit)
      joinAll(childClose, parentClose)
      assertEquals(listOf("child released", "parent closed its children"), events)
    }

  @Test
  fun closingChildrenFromACloseCallbackDoesNotWait() =
    runTest(timeout = 5.seconds) {
      val parent = GraphLifecycleState()
      val release = CompletableDeferred<Unit>()
      val child = SuspendingChild(parent) { release.await() }
      val childClose = launch { child.close() }
      runCurrent()
      // This would hang if it waited, because the child only finishes after it returns.
      runGraphCloseCallbacks { closeGraphChildren(parent) }
      assertFalse(child.state.isCloseFinished())
      release.complete(Unit)
      childClose.join()
      assertTrue(child.state.isCloseFinished())
    }

  /** Models the generated suspend `close()` of an extension of [parent]. */
  private class SuspendingChild(
    private val parent: GraphLifecycleState,
    private val onClose: suspend () -> Unit,
  ) : SuspendCloseableGraph {
    val state = GraphLifecycleState()

    init {
      parent.addChild(this, state)
    }

    override suspend fun close() {
      if (state.markClosed()) {
        try {
          runGraphCleanup {
            closeGraphChildren(state)
            runGraphCloseCallbacks { onClose() }
          }
        } finally {
          parent.removeChild(state)
          state.markCloseFinished()
        }
      }
    }
  }
}
