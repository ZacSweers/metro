// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import java.util.SortedSet
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SccTest {

  @Test
  fun singleNodeNoEdges() {
    val graph = mapOf<Int, SortedSet<Int>>()
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(0, components.size)
    assertEquals(0, componentOf.size)
  }

  @Test
  fun singleNodeSelfLoop() {
    val graph = mapOf(1 to sortedSetOf(1))
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(1, components.size)
    assertEquals(listOf(1), components[0].vertices)
    assertEquals(0, componentOf[1])
  }

  @Test
  fun multipleDisconnectedNodes() {
    val graph = mapOf(1 to TreeSet<Int>(), 2 to TreeSet(), 3 to TreeSet())
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(3, components.size)
    assertEquals(setOf(0, 1, 2), components.map { it.id }.toSet())
    assertEquals(0, componentOf[1])
    assertEquals(1, componentOf[2])
    assertEquals(2, componentOf[3])
  }

  @Test
  fun simpleGraph() {
    val graph = mapOf(1 to sortedSetOf(2), 2 to sortedSetOf(3), 3 to sortedSetOf(1))
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(1, components.size)
    assertEquals(listOf(3, 2, 1), components[0].vertices)
    assertEquals(0, componentOf[1])
    assertEquals(0, componentOf[2])
    assertEquals(0, componentOf[3])
  }

  @Test
  fun multipleComponents() {
    val graph =
      mapOf(
        1 to sortedSetOf(2),
        2 to sortedSetOf(1),
        3 to sortedSetOf(4),
        4 to sortedSetOf(5),
        5 to sortedSetOf(3),
        6 to sortedSetOf(),
      )
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(3, components.size)
    assertEquals(
      setOf(setOf(1, 2), setOf(3, 4, 5), setOf(6)),
      components.map { it.vertices.toSet() }.toSet(),
    )
    assertEquals(0, componentOf[1])
    assertEquals(0, componentOf[2])
    assertEquals(1, componentOf[3])
    assertEquals(1, componentOf[4])
    assertEquals(1, componentOf[5])
    assertEquals(2, componentOf[6])
  }

  @Test
  fun graphWithMultipleSelfLoops() {
    val graph = mapOf(1 to sortedSetOf(1), 2 to sortedSetOf(2), 3 to sortedSetOf(4), 4 to sortedSetOf(3))
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(3, components.size)
    assertEquals(
      setOf(setOf(1), setOf(2), setOf(3, 4)),
      components.map { it.vertices.toSet() }.toSet(),
    )
    assertEquals(0, componentOf[1])
    assertEquals(1, componentOf[2])
    assertEquals(2, componentOf[3])
    assertEquals(2, componentOf[4])
  }

  @Test
  fun throwsOnInvalidGraph() {
    val graph = mapOf(1 to sortedSetOf(2))

    assertFailsWith<NoSuchElementException> {
      graph.computeStronglyConnectedComponents()
      graph.getValue(2)
    }
  }
}
