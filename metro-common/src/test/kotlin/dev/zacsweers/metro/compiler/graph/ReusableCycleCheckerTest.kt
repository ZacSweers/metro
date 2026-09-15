// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    assertEquals(vertices, checker.findCycleWith(emptySet()))
    assertNull(checker.findCycleWith(setOf(deferredSource)))
  }

  @Test
  fun cyclePathExcludesLeadingVerticesAndSurvivesReuse() {
    val vertices = listOf("prefix", "A", "B")
    val adjacency = mapOf("prefix" to setOf("A"), "A" to setOf("B"), "B" to setOf("A"))
    val checker = ReusableCycleChecker(vertices, adjacency, mapOf("B" to setOf("A")))

    val cycle = checker.findCycleWith(emptySet())
    assertEquals(listOf("A", "B"), cycle)
    assertTrue(checker.isAcyclicWith(setOf("B")))
    assertFalse(checker.isAcyclicAfterRestoringEdges("B", emptySet()))
    assertEquals(listOf("A", "B"), checker.findCycleWith(emptySet()))
    assertEquals(listOf("A", "B"), cycle)
  }

  @Test
  fun selfCycleListsItsVertexOnce() {
    val checker = ReusableCycleChecker(listOf("A"), mapOf("A" to setOf("A")), emptyMap())

    assertEquals(listOf("A"), checker.findCycleWith(emptySet()))
  }

  @Test
  fun cycleCanStartAtANullVertex() {
    val vertices = listOf(null, "A")
    val adjacency = mapOf(null to setOf("A"), "A" to setOf(null))
    val checker = ReusableCycleChecker(vertices, adjacency, emptyMap())

    assertEquals(vertices, checker.findCycleWith(emptySet()))
  }

  @Test
  fun cycleSearchExaminesEachEdgeAtMostOnce() {
    val edges =
      linkedMapOf(
        "prefix" to setOf("left", "right"),
        "left" to setOf("leaf"),
        "right" to setOf("leaf", "A"),
        "leaf" to emptySet(),
        "A" to setOf("B"),
        "B" to setOf("A"),
      )
    val examinedEdges = mutableSetOf<Pair<String, String>>()
    val adjacency = edges.mapValues { (from, neighbors) ->
      object : Set<String> by neighbors {
        override fun iterator(): Iterator<String> {
          val delegate = neighbors.iterator()
          return object : Iterator<String> by delegate {
            override fun next(): String {
              val to = delegate.next()
              assertTrue(examinedEdges.add(from to to), "Revisited edge $from -> $to")
              return to
            }
          }
        }
      }
    }
    val checker = ReusableCycleChecker(edges.keys.toList(), adjacency, emptyMap())

    assertEquals(listOf("A", "B"), checker.findCycleWith(emptySet()))
    assertEquals(edges.values.sumOf { it.size }, examinedEdges.size)
  }

  @Test
  fun cycleSearchAndReconstructionCheckCancellation() {
    val vertices = listOf("prefix", "A", "B")
    val adjacency = mapOf("prefix" to setOf("A"), "A" to setOf("B"), "B" to setOf("A"))
    var checks = 0
    var cancelAt = Int.MAX_VALUE
    val checker =
      ReusableCycleChecker(vertices, adjacency, emptyMap()) {
        checks++
        if (checks == cancelAt) {
          throw CycleCheckCancelled()
        }
      }

    assertFalse(checker.isAcyclicWith(emptySet()))
    val traversalChecks = checks
    // The first check after traversal belongs to cycle reconstruction.
    for (cancellationCheck in listOf(2, traversalChecks + 1)) {
      checks = 0
      cancelAt = cancellationCheck
      assertFailsWith<CycleCheckCancelled> { checker.findCycleWith(emptySet()) }
      cancelAt = Int.MAX_VALUE
      assertEquals(listOf("A", "B"), checker.findCycleWith(emptySet()))
    }
  }

  private class CycleCheckCancelled : RuntimeException()

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
