// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphLifecycleTest {
  @Test
  fun `markClosed only succeeds once`() {
    val lifecycle = GraphLifecycle("AppGraph")
    assertTrue(lifecycle.markClosed())
    assertFalse(lifecycle.markClosed())
  }

  @Test
  fun `closeChildren closes each tracked child once`() {
    val lifecycle = GraphLifecycle("AppGraph")
    val closed = mutableListOf<String>()
    lifecycle.addChild(AutoCloseable { closed += "first" })
    lifecycle.addChild(AutoCloseable { closed += "second" })
    lifecycle.markClosed()
    lifecycle.closeChildren()
    lifecycle.closeChildren()
    assertEquals(listOf("first", "second"), closed.sorted())
  }

  @Test
  fun `removed children are not closed`() {
    val lifecycle = GraphLifecycle("AppGraph")
    val closed = mutableListOf<String>()
    val child = AutoCloseable { closed += "child" }
    lifecycle.addChild(child)
    lifecycle.removeChild(child)
    lifecycle.markClosed()
    lifecycle.closeChildren()
    assertTrue(closed.isEmpty())
  }

  @Test
  fun `creating children fails once closed`() {
    val lifecycle = GraphLifecycle("AppGraph")
    lifecycle.markClosed()
    val error = assertFailsWith<IllegalStateException> { lifecycle.checkOpen() }
    assertEquals("Cannot create a graph extension of AppGraph because it is closed.", error.message)
    assertFailsWith<IllegalStateException> { lifecycle.addChild(AutoCloseable {}) }
  }
}
