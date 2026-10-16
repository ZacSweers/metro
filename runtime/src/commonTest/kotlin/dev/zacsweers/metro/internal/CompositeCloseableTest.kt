// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CompositeCloseableTest {
  @Test
  fun duplicateRegistrationKeepsOneCallback() {
    val composite = CompositeCloseable()
    val key = Any()
    var calls = 0
    composite.add(key) { calls++ }
    composite.add(key) { calls += 100 }
    composite.close()
    assertEquals(1, calls)
  }

  @Test
  fun drainedCallbacksAreReleasedFromTheRegistry() {
    val registry = CloseCallbackRegistry<Any>()
    val callback = Any()
    val child = Any()
    registry.add(child, callback)
    val captured = registry.drain().single()
    assertSame(child, captured.key)
    assertSame(callback, captured.callback)
    assertEquals(emptyList(), registry.drain())
  }

  @Test
  fun removalUsesIdentity() {
    val composite = CompositeCloseable()
    val first = listOf(1)
    val second = listOf(1)
    val closed = mutableListOf<String>()
    composite.add(first) { closed += "first" }
    composite.add(second) { closed += "second" }
    composite.remove(first)
    composite.close()
    composite.close()
    assertEquals(listOf("second"), closed)
  }

  @Test
  fun childrenCanRemoveThemselvesDuringClose() {
    val composite = CompositeCloseable()
    val child = Any()
    var calls = 0
    composite.add(child) {
      calls++
      composite.remove(child)
    }
    composite.close()
    composite.close()
    assertEquals(1, calls)
  }

  @Test
  fun failureStopsCleanupAndReleasesRemainingCallbacks() {
    val composite = CompositeCloseable()
    var calls = 0
    composite.add(Any()) { error("failure") }
    composite.add(Any()) { calls++ }
    assertFailsWith<IllegalStateException> { composite.close() }
    composite.close()
    assertEquals(0, calls)
  }
}
