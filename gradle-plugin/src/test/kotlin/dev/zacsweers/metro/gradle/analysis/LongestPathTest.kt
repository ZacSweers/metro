// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph
import com.google.common.graph.GraphBuilder
import com.google.common.graph.ImmutableGraph
import com.google.common.graph.MutableGraph
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LongestPathTest {

  @Test
  fun emptyGraph() {
    val graph = ImmutableGraph.copyOf(GraphBuilder.directed().build<String>())
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(0)
    assertThat(lp.paths()).isEmpty()
  }

  @Test
  fun singleNode() {
    val graph = buildGraph { addNode("A") }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(1)
    assertThat(lp.lengthFrom("A")).isEqualTo(1)
    assertThat(lp.paths()).containsExactly(listOf("A"))
  }

  @Test
  fun simpleChain() {
    // A -> B -> C -> D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(4)
    assertThat(lp.lengthFrom("A")).isEqualTo(4)
    assertThat(lp.lengthFrom("B")).isEqualTo(3)
    assertThat(lp.lengthFrom("C")).isEqualTo(2)
    assertThat(lp.lengthFrom("D")).isEqualTo(1)
    assertThat(lp.paths()).containsExactly(listOf("A", "B", "C", "D"))
  }

  @Test
  fun diamond() {
    //     A
    //    / \
    //   B   C
    //    \ /
    //     D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("B", "D")
      putEdge("C", "D")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(3)
    assertThat(lp.lengthFrom("A")).isEqualTo(3)
    assertThat(lp.lengthFrom("B")).isEqualTo(2)
    assertThat(lp.lengthFrom("C")).isEqualTo(2)
    assertThat(lp.lengthFrom("D")).isEqualTo(1)
    // Should return both paths A->B->D and A->C->D
    assertThat(lp.paths(10)).containsExactly(listOf("A", "B", "D"), listOf("A", "C", "D"))
  }

  @Test
  fun multipleRoots() {
    // A -> C -> D
    // B -> C
    val graph = buildGraph {
      putEdge("A", "C")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(3)
    assertThat(lp.lengthFrom("A")).isEqualTo(3)
    assertThat(lp.lengthFrom("B")).isEqualTo(3)
    assertThat(lp.lengthFrom("C")).isEqualTo(2)
    assertThat(lp.lengthFrom("D")).isEqualTo(1)
    // Both A and B are roots with max path length
    assertThat(lp.paths(10)).containsExactly(listOf("A", "C", "D"), listOf("B", "C", "D"))
  }

  @Test
  fun multipleRootsWithDifferentLengths() {
    // A -> B -> C -> D (length 4)
    // E -> C (length 3, shorter)
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
      putEdge("E", "C")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(4)
    assertThat(lp.lengthFrom("A")).isEqualTo(4)
    assertThat(lp.lengthFrom("E")).isEqualTo(3)
    // Only paths from A should be returned (has max length)
    assertThat(lp.paths()).containsExactly(listOf("A", "B", "C", "D"))
  }

  @Test
  fun branchingPaths() {
    //     A
    //    /|\
    //   B C D
    //   |   |
    //   E   F
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
      putEdge("B", "E")
      putEdge("D", "F")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(3)
    assertThat(lp.lengthFrom("A")).isEqualTo(3)
    assertThat(lp.lengthFrom("B")).isEqualTo(2)
    assertThat(lp.lengthFrom("C")).isEqualTo(1)
    assertThat(lp.lengthFrom("D")).isEqualTo(2)
    // Two paths of length 3: A->B->E and A->D->F
    assertThat(lp.paths(10)).containsExactly(listOf("A", "B", "E"), listOf("A", "D", "F"))
  }

  @Test
  fun maxPathsLimit() {
    //     A
    //    /|\
    //   B C D
    //   |   |
    //   E   F
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
      putEdge("B", "E")
      putEdge("D", "F")
    }
    val lp = LongestPath(graph)

    // Should only return 1 path when maxPaths=1
    assertThat(lp.paths(1)).hasSize(1)
    assertThat(lp.paths(1).first()).isEqualTo(listOf("A", "B", "E"))
  }

  @Test
  fun maxPathsZeroReturnsEmpty() {
    val graph = buildGraph { putEdge("A", "B") }
    val lp = LongestPath(graph)

    assertThat(lp.paths(0)).isEmpty()
  }

  @Test
  fun complexDag() {
    //       A
    //      / \
    //     B   C
    //    / \ / \
    //   D   E   F
    //    \ / \ /
    //     G   H
    //      \ /
    //       I
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("B", "D")
      putEdge("B", "E")
      putEdge("C", "E")
      putEdge("C", "F")
      putEdge("D", "G")
      putEdge("E", "G")
      putEdge("E", "H")
      putEdge("F", "H")
      putEdge("G", "I")
      putEdge("H", "I")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(5) // A -> B -> D/E -> G -> I or similar
    assertThat(lp.lengthFrom("A")).isEqualTo(5)
    assertThat(lp.lengthFrom("I")).isEqualTo(1)
  }

  @Test
  fun nodeNotInGraph() {
    val graph = buildGraph { putEdge("A", "B") }
    val lp = LongestPath(graph)

    assertThat(lp.lengthFrom("Z")).isNull()
  }

  @Test
  fun wideGraph() {
    // A -> B, C, D, E, F (all leaves)
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
      putEdge("A", "E")
      putEdge("A", "F")
    }
    val lp = LongestPath(graph)

    assertThat(lp.longestPathLength).isEqualTo(2)
    assertThat(lp.paths(10)).hasSize(5)
    assertThat(lp.paths(10))
      .containsExactly(
        listOf("A", "B"),
        listOf("A", "C"),
        listOf("A", "D"),
        listOf("A", "E"),
        listOf("A", "F"),
      )
  }

  @Test
  fun pathsAreDeterministicallySorted() {
    // Multiple paths should be returned in consistent alphabetical order
    val graph = buildGraph {
      putEdge("A", "Z")
      putEdge("A", "M")
      putEdge("A", "B")
    }
    val lp = LongestPath(graph)

    val paths = lp.paths(10)
    assertThat(paths)
      .containsExactly(listOf("A", "B"), listOf("A", "M"), listOf("A", "Z"))
      .inOrder()
  }

  private fun buildGraph(block: MutableGraph<String>.() -> Unit): Graph<String> {
    val graph = GraphBuilder.directed().allowsSelfLoops(false).build<String>()
    graph.block()
    return graph
  }
}
