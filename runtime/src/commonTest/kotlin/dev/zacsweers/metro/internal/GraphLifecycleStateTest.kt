// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphLifecycleStateTest {
  private data class Child(val key: Int)

  @Test
  fun onlyTheFirstCallClosesTheGraph() {
    val state = GraphLifecycleState()
    assertTrue(state.markClosed())
    assertFalse(state.markClosed())
    assertFailsWith<IllegalStateException> { state.checkOpen() }
  }

  @Test
  fun equalChildrenAreTrackedSeparately() {
    val state = GraphLifecycleState()
    val first = Child(1)
    val second = Child(1)
    state.addChild(first, GraphLifecycleState())
    val secondState = GraphLifecycleState()
    state.addChild(second, secondState)
    secondState.leaveParent()
    assertSame(first, state.takeChildren().single())
    assertEquals(emptyList(), state.takeChildren())
  }

  @Test
  fun childrenThatLeaveAreNotTakenLater() {
    val state = GraphLifecycleState()
    val kept = mutableListOf<Any>()
    // Enough children to prune the links of the ones that left several times over.
    repeat(1_000) { index ->
      val child = Child(index)
      val childState = GraphLifecycleState()
      state.addChild(child, childState)
      if (index % 10 == 0) {
        kept += child
      } else {
        childState.leaveParent()
      }
    }
    // Most recently added first.
    assertEquals(kept.reversed(), state.takeChildren())
  }

  @Test
  fun aChildClosedDuringInitIsNotKept() {
    val parent = GraphLifecycleState()
    val childState = GraphLifecycleState()
    childState.markClosed()
    parent.addChild(Child(1), childState)
    assertEquals(emptyList(), parent.takeChildren())
  }

  @Test
  fun closingChildrenStopsTrackingThem() {
    val state = GraphLifecycleState()
    var closes = 0
    val child =
      object : AutoCloseable {
        override fun close() {
          closes++
        }
      }
    state.addChild(child, GraphLifecycleState())
    state.closeChildren()
    state.closeChildren()
    assertEquals(1, closes)
  }

  @Test
  fun aChildRegisteredAfterCloseIsClosed() {
    val parent = GraphLifecycleState()
    parent.markClosed()
    var closes = 0
    val child =
      object : AutoCloseable {
        override fun close() {
          closes++
          error("failed")
        }
      }
    val rejection =
      assertFailsWith<IllegalStateException> {
        parent.addCloseableChild(child, GraphLifecycleState())
      }
    assertEquals(1, closes)
    // The creator sees the registration failure. The child's own failure rides along.
    assertEquals("Cannot create an extension of a closed graph.", rejection.message)
    assertEquals("failed", rejection.suppressedExceptions.single().message)
    assertEquals(emptyList(), parent.takeChildren())
  }

  @Test
  fun aFailingChildDoesNotStopTheOthers() {
    val state = GraphLifecycleState()
    var closes = 0
    val failing =
      object : AutoCloseable {
        override fun close() {
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
    state.closeChildren()
    assertEquals(2, closes)
    val failure = assertFailsWith<IllegalStateException> { state.throwCloseFailure() }
    assertEquals("failed", failure.message)
    // A recorded failure is only thrown once.
    state.throwCloseFailure()
  }

  @Test
  fun laterFailuresAreSuppressedByTheFirst() {
    val state = GraphLifecycleState()
    val first = IllegalStateException("first")
    val second = IllegalStateException("second")
    state.recordCloseFailure(first)
    state.recordCloseFailure(second)
    // The same exception thrown twice can't suppress itself.
    state.recordCloseFailure(first)
    val thrown = assertFailsWith<IllegalStateException> { state.throwCloseFailure() }
    assertSame(first, thrown)
    assertEquals(listOf<Throwable>(second), thrown.suppressedExceptions)
  }

  @Test
  fun aClosedGraphRejectsNewChildren() {
    val state = GraphLifecycleState()
    state.markClosed()
    assertFailsWith<IllegalStateException> { state.addChild(Child(1), GraphLifecycleState()) }
    assertEquals(emptyList(), state.takeChildren())
  }
}
