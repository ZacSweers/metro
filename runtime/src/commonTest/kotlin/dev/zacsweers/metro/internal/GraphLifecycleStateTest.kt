// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphLifecycleStateTest {
  private data class Child(val key: Int)

  @Test
  fun aChildClosedDuringStartupIsReleasedOnRegistration() {
    val parent = GraphLifecycleState()
    val state = GraphLifecycleState()
    val child = Child(1)
    state.close(child, parent) {}
    parent.register(child, state)
    assertTrue(parent.claimChildren()!!.isEmpty())
  }

  @Test
  fun independentCloseRemovesTheChildByIdentityOnFailure() {
    val parent = GraphLifecycleState()
    val first = Child(1)
    val second = Child(1)
    val secondState = GraphLifecycleState()
    parent.register(first, GraphLifecycleState())
    parent.register(second, secondState)
    assertFailsWith<IllegalStateException> {
      secondState.close(second, parent) { error("cleanup failed") }
    }
    assertSame(first, parent.claimChildren()!!.single())
    assertFailsWith<IllegalStateException> { parent.register(second, secondState) }
  }
}
