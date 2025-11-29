// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph
import com.google.common.graph.GraphBuilder
import com.google.common.graph.MutableGraph
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CentralityTest {

  @Test
  fun emptyGraph() {
    val graph = buildGraph {}
    val centrality = Centrality(graph)

    assertThat(centrality.nodes()).isEmpty()
    assertThat(centrality.nodesByScore()).isEmpty()
    assertThat(centrality.nodesByNormalizedScore()).isEmpty()
  }

  @Test
  fun singleNode() {
    val graph = buildGraph { addNode("A") }
    val centrality = Centrality(graph)

    assertThat(centrality.nodes()).containsExactly("A")
    assertThat(centrality.scoreOf("A")).isEqualTo(0.0)
    assertThat(centrality.normalizedScoreOf("A")).isEqualTo(0.0)
  }

  @Test
  fun simpleChain() {
    // A -> B -> C -> D
    // B and C are on the path between A and D, so they have higher centrality
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val centrality = Centrality(graph)

    assertThat(centrality.nodes()).containsExactly("A", "B", "C", "D")

    // A and D are endpoints, so they have 0 centrality
    assertThat(centrality.scoreOf("A")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("D")).isEqualTo(0.0)

    // B is on paths: A→C (1), A→D (1) = 2.0
    // C is on paths: A→D (1), B→D (1) = 2.0
    assertThat(centrality.scoreOf("B")).isEqualTo(2.0)
    assertThat(centrality.scoreOf("C")).isEqualTo(2.0)
  }

  @Test
  fun diamond() {
    //     A
    //    / \
    //   B   C
    //    \ /
    //     D
    // Neither B nor C is on all paths from A to D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("B", "D")
      putEdge("C", "D")
    }
    val centrality = Centrality(graph)

    assertThat(centrality.nodes()).containsExactly("A", "B", "C", "D")

    // A and D are endpoints
    assertThat(centrality.scoreOf("A")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("D")).isEqualTo(0.0)

    // B and C each lie on 1 of 2 shortest paths from A→D = 0.5 each
    assertThat(centrality.scoreOf("B")).isEqualTo(0.5)
    assertThat(centrality.scoreOf("C")).isEqualTo(0.5)
  }

  @Test
  fun bottleneck() {
    //   A   B
    //    \ /
    //     C
    //    / \
    //   D   E
    // C is a bottleneck - all paths between {A,B} and {D,E} go through C
    val graph = buildGraph {
      putEdge("A", "C")
      putEdge("B", "C")
      putEdge("C", "D")
      putEdge("C", "E")
    }
    val centrality = Centrality(graph)

    // Endpoints have 0 centrality
    assertThat(centrality.scoreOf("A")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("B")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("D")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("E")).isEqualTo(0.0)

    // C is on paths: A→D (1), A→E (1), B→D (1), B→E (1) = 4.0
    assertThat(centrality.scoreOf("C")).isEqualTo(4.0)
    assertThat(centrality.normalizedScoreOf("C")).isEqualTo(1.0)

    // C has highest centrality
    val byScore = centrality.nodesByScore()
    assertThat(byScore.first().first).isEqualTo("C")
    assertThat(byScore.first().second).isEqualTo(4.0)
  }

  @Test
  fun nodeNotInGraph() {
    val graph = buildGraph { putEdge("A", "B") }
    val centrality = Centrality(graph)

    assertThat(centrality.scoreOf("Z")).isNull()
    assertThat(centrality.normalizedScoreOf("Z")).isNull()
  }

  @Test
  fun nodesByScoreIsSortedDescending() {
    // A -> B -> C -> D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val centrality = Centrality(graph)

    val byScore = centrality.nodesByScore()
    // Scores should be in descending order
    for (i in 0 until byScore.size - 1) {
      assertThat(byScore[i].second).isAtLeast(byScore[i + 1].second)
    }
  }

  @Test
  fun normalizedScoresAreInZeroToOne() {
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
      putEdge("A", "C") // Add alternate path
    }
    val centrality = Centrality(graph)

    for ((_, score) in centrality.nodesByNormalizedScore()) {
      assertThat(score).isAtLeast(0.0)
      assertThat(score).isAtMost(1.0)
    }

    // At least one node should have normalized score of 1.0 (the max)
    assertThat(centrality.nodesByNormalizedScore().any { it.second == 1.0 }).isTrue()
  }

  @Test
  fun disconnectedComponents() {
    // A -> B (component 1)
    // C -> D (component 2)
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("C", "D")
    }
    val centrality = Centrality(graph)

    // All nodes have 0 centrality (no paths between components, endpoints within)
    assertThat(centrality.scoreOf("A")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("B")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("C")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("D")).isEqualTo(0.0)
  }

  @Test
  fun starGraph() {
    // A is the center, connected to B, C, D, E
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
      putEdge("A", "E")
    }
    val centrality = Centrality(graph)

    // A is not on any shortest path (all paths are direct from A to leaves)
    // Leaves have no paths through them
    assertThat(centrality.scoreOf("A")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("B")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("C")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("D")).isEqualTo(0.0)
    assertThat(centrality.scoreOf("E")).isEqualTo(0.0)
  }

  private fun buildGraph(block: MutableGraph<String>.() -> Unit): Graph<String> {
    val graph = GraphBuilder.directed().allowsSelfLoops(false).build<String>()
    graph.block()
    return graph
  }
}
