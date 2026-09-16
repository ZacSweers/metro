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
      observeEdges(neighbors) { to ->
        assertTrue(examinedEdges.add(from to to), "Revisited edge $from -> $to")
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

  @Test
  fun initialChecksStopBeforeUnusedMaskedNeighbors() {
    val masked = List(32) { "soft$it" }
    val neighbors = linkedSetOf(masked.first(), "B")
    neighbors.addAll(masked.drop(1))
    val examinedNeighbors = mutableListOf<String>()
    val adjacency =
      linkedMapOf(
        "A" to observeEdges(neighbors) { examinedNeighbors += it },
        "B" to setOf("A"),
      )
    for (neighbor in masked) {
      adjacency[neighbor] = setOf("A")
    }
    val checker =
      ReusableCycleChecker(adjacency.keys.toList(), adjacency, mapOf("A" to masked.toSet()))

    // The eager A-B cycle is found after skipping only the first masked neighbor.
    assertFalse(checker.isAcyclicWith(setOf("A")))
    assertEquals(listOf(masked.first(), "B"), examinedNeighbors)
    examinedNeighbors.clear()
    assertEquals(listOf("A", "B"), checker.findCycleWith(setOf("A")))
    assertEquals(listOf(masked.first(), "B"), examinedNeighbors)
  }

  @Test
  fun repeatedRestorationsAvoidRescanningMaskedDenseRows() {
    val vertices = List(32) { it }
    val edges = vertices.associateWith { from -> vertices.filterTo(linkedSetOf()) { it != from } }
    var examinedEdges = 0
    val adjacency = edges.mapValues { (_, neighbors) ->
      observeEdges(neighbors) { examinedEdges++ }
    }
    val checker = ReusableCycleChecker(vertices, adjacency, edges)
    val allDeferred = vertices.toSet()
    assertTrue(checker.isAcyclicWith(allDeferred))

    // Keep the greatest vertex active while testing each remaining candidate's restoration.
    val deferred = allDeferred - vertices.last()
    assertTrue(checker.isAcyclicAfterRestoringEdges(vertices.last(), deferred))
    examinedEdges = 0
    for (candidate in vertices.dropLast(1)) {
      assertFalse(checker.isAcyclicAfterRestoringEdges(candidate, deferred - candidate))
    }

    val edgeCount = edges.values.sumOf { it.size }
    assertTrue(
      examinedEdges <= edgeCount * 2,
      "Restoring ${deferred.size} candidates examined $examinedEdges edges in a $edgeCount-edge graph",
    )
  }

  @Test
  fun mixedDeferredRowsPreserveNeighborOrderAcrossMaskChanges() {
    val vertices = listOf("A", "softFirst", "Z", "softMiddle", "B")
    val adjacency =
      mapOf(
        "A" to linkedSetOf("softFirst", "Z", "softMiddle", "B"),
        "softFirst" to setOf("A"),
        "Z" to setOf("A"),
        "softMiddle" to setOf("A"),
        "B" to setOf("A"),
      )
    val deferredEdges =
      mapOf(
        "A" to setOf("softFirst", "softMiddle"),
        "Z" to setOf("A"),
        "B" to setOf("A"),
      )
    val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)

    assertTrue(checker.isAcyclicWith(setOf("A", "Z", "B")))
    assertFalse(checker.isAcyclicAfterRestoringEdges("Z", setOf("A", "B")))

    // The eager Z edge precedes B in the supplied adjacency order.
    assertEquals(listOf("A", "Z"), checker.findCycleWith(setOf("A")))
    assertEquals(listOf("A", "B"), checker.findCycleWith(setOf("A", "Z")))
    assertTrue(checker.isAcyclicWith(setOf("A", "Z", "B")))
    assertFalse(checker.isAcyclicAfterRestoringEdges("Z", setOf("A", "B")))
    assertEquals(listOf("A", "Z"), checker.findCycleWith(setOf("A", "B")))
    assertEquals(listOf("A", "softFirst"), checker.findCycleWith(emptySet()))
    assertEquals(listOf("A", "B"), checker.findCycleWith(setOf("A", "Z")))
  }

  @Test
  fun cachedRowsSupportNullSourcesAndTargets() {
    val vertices = listOf(null, "A", "soft")
    val adjacency =
      mapOf(null to setOf("A"), "A" to linkedSetOf("soft", null), "soft" to setOf(null))
    val deferredEdges = mapOf(null to setOf("A"), "A" to setOf("soft"))
    val checker = ReusableCycleChecker(vertices, adjacency, deferredEdges)

    assertTrue(checker.isAcyclicWith(setOf(null, "A")))
    assertFalse(checker.isAcyclicAfterRestoringEdges(null, setOf("A")))
    assertEquals(listOf(null, "A"), checker.findCycleWith(setOf("A")))
    assertNull(checker.findCycleWith(setOf(null, "A")))
    assertFalse(checker.isAcyclicAfterRestoringEdges(null, setOf("A")))
    assertEquals(vertices, checker.findCycleWith(emptySet()))
    assertNull(checker.findCycleWith(setOf(null, "A")))
  }

  @Test
  fun cancellationDuringMaskedRowScanLeavesLaterChecksComplete() {
    var examinedEdges = 0
    val adjacency =
      mapOf(
        "A" to observeEdges(linkedSetOf("soft", "B")) { examinedEdges++ },
        "soft" to setOf("A"),
        "B" to setOf("A"),
      )
    val vertices = listOf("A", "soft", "B")
    val deferredEdges = mapOf("A" to setOf("soft"), "B" to setOf("A"))

    // Verify the restoration precondition separately to keep the checked instance's rows uncached.
    val preconditionChecker = ReusableCycleChecker(vertices, adjacency, deferredEdges)
    assertTrue(preconditionChecker.isAcyclicWith(setOf("A", "B")))
    examinedEdges = 0
    var cancel = true
    val checker =
      ReusableCycleChecker(vertices, adjacency, deferredEdges) {
        if (cancel && examinedEdges > 0) {
          throw CycleCheckCancelled()
        }
      }

    // Cancel after reading a masked neighbor before reaching the eager edge that closes a cycle.
    assertFailsWith<CycleCheckCancelled> {
      checker.isAcyclicAfterRestoringEdges("B", setOf("A"))
    }
    cancel = false
    assertEquals(listOf("A", "B"), checker.findCycleWith(setOf("A")))
    assertEquals(listOf("A", "soft"), checker.findCycleWith(emptySet()))
  }

  /** Observes original adjacency reads across repeated traversals. */
  private fun <V> observeEdges(neighbors: Set<V>, onRead: (V) -> Unit): Set<V> {
    return object : Set<V> by neighbors {
      override fun iterator(): Iterator<V> {
        val delegate = neighbors.iterator()
        return object : Iterator<V> by delegate {
          override fun next(): V {
            val neighbor = delegate.next()
            onRead(neighbor)
            return neighbor
          }
        }
      }
    }
  }
}
