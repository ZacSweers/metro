// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import java.util.SortedSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SccTest {

  @Test
  fun singleNodeNoEdges() {
    val graph = sortedMapOf<Int, SortedSet<Int>>()
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(0, components.size)
    assertEquals(0, componentOf.size)
  }

  @Test
  fun singleNodeSelfLoop() {
    val graph = sortedMapOf(1 to untypedSortedSetOf(1))
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(1, components.size)
    assertEquals(listOf(1), components[0].vertices)
    assertEquals(0, componentOf[1])
  }

  @Test
  fun multipleDisconnectedNodes() {
    val graph =
      sortedMapOf(
        1 to untypedSortedSetOf<Int>(),
        2 to untypedSortedSetOf(),
        3 to untypedSortedSetOf(),
      )
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(3, components.size)
    assertEquals(setOf(0, 1, 2), components.map { it.id }.toSet())
    assertEquals(0, componentOf[1])
    assertEquals(1, componentOf[2])
    assertEquals(2, componentOf[3])
  }

  @Test
  fun simpleGraph() {
    val graph =
      sortedMapOf(
        1 to untypedSortedSetOf(2),
        2 to untypedSortedSetOf(3),
        3 to untypedSortedSetOf(1),
      )
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
      sortedMapOf(
        1 to untypedSortedSetOf(2),
        2 to untypedSortedSetOf(1),
        3 to untypedSortedSetOf(4),
        4 to untypedSortedSetOf(5),
        5 to untypedSortedSetOf(3),
        6 to untypedSortedSetOf(),
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
    val graph =
      sortedMapOf(
        1 to untypedSortedSetOf(1),
        2 to untypedSortedSetOf(2),
        3 to untypedSortedSetOf(4),
        4 to untypedSortedSetOf(3),
      )
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
    val graph = sortedMapOf(1 to untypedSortedSetOf(2))

    assertFailsWith<NoSuchElementException> {
      val _ = graph.computeStronglyConnectedComponents()
      val _ = graph.getValue(2)
    }
  }

  private fun <T : Comparable<T>> untypedSortedSetOf(vararg elements: T): SortedSet<T> {
    return elements.toSortedSet()
  }
}
