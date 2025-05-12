// TopologicalSortKtTest.kt
package dev.zacsweers.metro.compiler.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SccTest {

  @Test
  fun singleNodeNoEdges() {
    val graph = mapOf<Int, List<Int>>()
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(0, components.size)
    assertEquals(0, componentOf.size)
  }

  @Test
  fun singleNodeSelfLoop() {
    val graph = mapOf(1 to listOf(1))
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(1, components.size)
    assertEquals(listOf(1), components[0].vertices)
    assertEquals(0, componentOf[1])
  }

  @Test
  fun multipleDisconnectedNodes() {
    val graph = mapOf(1 to emptyList<Int>(), 2 to emptyList(), 3 to emptyList())
    val (components, componentOf) = graph.computeStronglyConnectedComponents()

    assertEquals(3, components.size)
    assertEquals(setOf(0, 1, 2), components.map { it.id }.toSet())
    assertEquals(0, componentOf[1])
    assertEquals(1, componentOf[2])
    assertEquals(2, componentOf[3])
  }

  @Test
  fun simpleGraph() {
    val graph = mapOf(1 to listOf(2), 2 to listOf(3), 3 to listOf(1))
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
        1 to listOf(2),
        2 to listOf(1),
        3 to listOf(4),
        4 to listOf(5),
        5 to listOf(3),
        6 to emptyList(),
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
    val graph = mapOf(1 to listOf(1), 2 to listOf(2), 3 to listOf(4), 4 to listOf(3))
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
    val graph = mapOf(1 to listOf(2))

    assertFailsWith<NoSuchElementException> {
      graph.computeStronglyConnectedComponents()
      graph.getValue(2)
    }
  }
}
