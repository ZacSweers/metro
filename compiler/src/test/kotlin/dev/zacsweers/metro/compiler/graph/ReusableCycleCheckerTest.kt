// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test
  fun restoredEdgesDetectCyclesAndClearUnfinishedTraversalState() {
    val vertices = listOf("candidate", "dependency")
    val adjacency = mapOf("candidate" to setOf("dependency"), "dependency" to setOf("candidate"))
    val deferredEdges = mapOf("candidate" to setOf("dependency"))
    val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)

    assertTrue(checker.isAcyclicWith(setOf("candidate")))
    assertFalse(checker.isAcyclicAfterRestoringEdges("candidate", emptySet()))

    // Detecting the restored cycle leaves frames that the next traversal must discard.
    assertTrue(checker.isAcyclicWith(setOf("candidate")))
    assertFalse(checker.isAcyclicAfterRestoringEdges("candidate", emptySet()))
  }

  @Test
  fun restoredEdgeChecksSkipUnrelatedVertices() {
    val vertices = listOf("unrelated", "unrelatedChild", "candidate", "dependency")
    val edges: Map<String, Set<String>> =
      mapOf(
        "unrelated" to setOf("unrelatedChild"),
        "unrelatedChild" to emptySet(),
        "candidate" to setOf("dependency"),
        "dependency" to emptySet(),
      )
    val visited = mutableListOf<String>()
    val adjacency =
      object : Map<String, Set<String>> by edges {
        override fun get(key: String): Set<String>? {
          visited += key
          return edges[key]
        }
      }
    val deferredEdges = mapOf("candidate" to setOf("dependency"))
    val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)

    assertTrue(checker.isAcyclicWith(setOf("candidate")))
    visited.clear()

    // Only bindings reachable from the restored candidate need another cycle check.
    assertTrue(checker.isAcyclicAfterRestoringEdges("candidate", emptySet()))
    assertEquals(listOf("candidate", "dependency"), visited)
  }

  @Test
  fun restoredEdgesKeepOtherDeferredEdgesMasked() {
    val vertices = listOf("candidate", "dependency")
    val adjacency = mapOf("candidate" to setOf("dependency"), "dependency" to setOf("candidate"))
    val deferredEdges = adjacency
    val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)

    assertTrue(checker.isAcyclicWith(setOf("candidate", "dependency")))

    // Restoring one edge is safe while the other side of the cycle remains deferred.
    assertTrue(checker.isAcyclicAfterRestoringEdges("candidate", setOf("dependency")))
    assertFalse(checker.isAcyclicAfterRestoringEdges("candidate", emptySet()))
    // A failed local check must not affect the next local check.
    assertTrue(checker.isAcyclicAfterRestoringEdges("candidate", setOf("dependency")))
  }
}
