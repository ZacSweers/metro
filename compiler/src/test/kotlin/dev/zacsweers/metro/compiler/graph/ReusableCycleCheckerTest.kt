// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReusableCycleCheckerTest {
  @Test
  fun `large deferred cycles do not overflow the JVM stack`() {
    // Build a dependency ring deep enough to overflow a recursive depth-first traversal.
    val vertexCount = 20_000
    val vertices = List(vertexCount) { it }
    val adjacency = vertices.associateWith { vertex -> setOf((vertex + 1) % vertexCount) }

    // Deferring the last vertex masks its edge back to the first vertex, breaking the cycle.
    val deferredSource = vertexCount - 1
    val deferredEdges = mapOf(deferredSource to setOf(0))
    val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)

    assertTrue(checker.isAcyclicWith(setOf(deferredSource)))
    assertFalse(checker.isAcyclicWith(emptySet()))

    // A failed check can leave unfinished frames, so the next check must reset traversal state.
    assertTrue(checker.isAcyclicWith(setOf(deferredSource)))
  }
}
