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
  fun childrenAreTrackedByIdentity() {
    val state = GraphLifecycleState()
    val first = Child(1)
    val second = Child(1)
    state.addChild(first, GraphLifecycleState())
    val secondState = GraphLifecycleState()
    state.addChild(second, secondState)
    state.addChild(second, secondState)
    state.removeChild(second)
    assertSame(first, state.takeChildren().single())
    assertEquals(emptyList(), state.takeChildren())
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
  fun aClosedGraphRejectsNewChildren() {
    val state = GraphLifecycleState()
    state.markClosed()
    assertFailsWith<IllegalStateException> { state.addChild(Child(1), GraphLifecycleState()) }
    assertEquals(emptyList(), state.takeChildren())
  }
}
