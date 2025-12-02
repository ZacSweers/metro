// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph
import com.google.common.graph.GraphBuilder
import com.google.common.graph.MutableGraph
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DominatorsTest {

  @Test
  fun emptyGraph() {
    val graph = buildGraph {}
    val dom = Dominators(graph)

    assertThat(dom.nodes()).isEmpty()
    assertThat(dom.nodesByDominatedCount()).isEmpty()
  }

  @Test
  fun singleNode() {
    val graph = buildGraph { addNode("A") }
    val dom = Dominators(graph)

    assertThat(dom.nodes()).containsExactly("A")
    assertThat(dom.dominatedBy("A")).isEmpty()
    assertThat(dom.immediateDominatorOf("A")).isNull()
  }

  @Test
  fun simpleChain() {
    // A -> B -> C -> D
    // A dominates B, C, D
    // B dominates C, D
    // C dominates D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val dom = Dominators(graph)

    assertThat(dom.dominatedBy("A")).containsExactly("B", "C", "D")
    assertThat(dom.dominatedBy("B")).containsExactly("C", "D")
    assertThat(dom.dominatedBy("C")).containsExactly("D")
    assertThat(dom.dominatedBy("D")).isEmpty()

    assertThat(dom.immediateDominatorOf("A")).isNull()
    assertThat(dom.immediateDominatorOf("B")).isEqualTo("A")
    assertThat(dom.immediateDominatorOf("C")).isEqualTo("B")
    assertThat(dom.immediateDominatorOf("D")).isEqualTo("C")
  }

  @Test
  fun diamond() {
    //     A
    //    / \
    //   B   C
    //    \ /
    //     D
    // A dominates B, C, D (all paths to B, C, D go through A)
    // B does not dominate D (path A->C->D doesn't go through B)
    // C does not dominate D (path A->B->D doesn't go through C)
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("B", "D")
      putEdge("C", "D")
    }
    val dom = Dominators(graph)

    assertThat(dom.dominatedBy("A")).containsExactly("B", "C", "D")
    assertThat(dom.dominatedBy("B")).isEmpty() // D can be reached via C
    assertThat(dom.dominatedBy("C")).isEmpty() // D can be reached via B
    assertThat(dom.dominatedBy("D")).isEmpty()

    assertThat(dom.immediateDominatorOf("D")).isEqualTo("A")
  }

  @Test
  fun multipleRoots() {
    // A -> C -> D
    // B -> C
    // Neither A nor B dominates C (C can be reached from either)
    // C dominates D
    val graph = buildGraph {
      putEdge("A", "C")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val dom = Dominators(graph)

    // A and B are both roots, so neither dominates C
    assertThat(dom.dominatedBy("A")).isEmpty()
    assertThat(dom.dominatedBy("B")).isEmpty()
    // C dominates D since all paths to D must go through C
    assertThat(dom.dominatedBy("C")).containsExactly("D")
    assertThat(dom.dominatedBy("D")).isEmpty()
  }

  @Test
  fun bottleneck() {
    //   A   B
    //    \ /
    //     C
    //    / \
    //   D   E
    // C is a bottleneck - dominates D and E
    val graph = buildGraph {
      putEdge("A", "C")
      putEdge("B", "C")
      putEdge("C", "D")
      putEdge("C", "E")
    }
    val dom = Dominators(graph)

    assertThat(dom.dominatedBy("A")).isEmpty()
    assertThat(dom.dominatedBy("B")).isEmpty()
    assertThat(dom.dominatedBy("C")).containsExactly("D", "E")
    assertThat(dom.dominatedBy("D")).isEmpty()
    assertThat(dom.dominatedBy("E")).isEmpty()

    // C is the biggest dominator
    val byCount = dom.nodesByDominatedCount()
    assertThat(byCount.first().first).isEqualTo("C")
    assertThat(byCount.first().second).isEqualTo(2)
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
    val dom = Dominators(graph)

    // A dominates everything
    assertThat(dom.dominatedBy("A")).containsExactly("B", "C", "D", "E", "F", "G", "H", "I")

    // B dominates D (only path to D)
    assertThat(dom.dominatedBy("B")).containsExactly("D")

    // C dominates F (only path to F)
    assertThat(dom.dominatedBy("C")).containsExactly("F")

    // E doesn't dominate anything (G can be reached via D, H can be reached via F)
    assertThat(dom.dominatedBy("E")).isEmpty()

    // D, F, G, H don't dominate anything
    assertThat(dom.dominatedBy("D")).isEmpty()
    assertThat(dom.dominatedBy("F")).isEmpty()
    assertThat(dom.dominatedBy("G")).isEmpty()
    assertThat(dom.dominatedBy("H")).isEmpty()
    assertThat(dom.dominatedBy("I")).isEmpty()
  }

  @Test
  fun nodeNotInGraph() {
    val graph = buildGraph { putEdge("A", "B") }
    val dom = Dominators(graph)

    assertThat(dom.dominatedBy("Z")).isEmpty()
    assertThat(dom.immediateDominatorOf("Z")).isNull()
  }

  @Test
  fun nodesByDominatedCountIsSorted() {
    // A -> B -> C -> D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val dom = Dominators(graph)

    val byCount = dom.nodesByDominatedCount()
    assertThat(byCount.map { it.first }).containsExactly("A", "B", "C", "D").inOrder()
    assertThat(byCount.map { it.second }).containsExactly(3, 2, 1, 0).inOrder()
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
    val dom = Dominators(graph)

    assertThat(dom.dominatedBy("A")).containsExactly("B", "C", "D", "E", "F")
    assertThat(dom.dominatedBy("B")).isEmpty()
    assertThat(dom.dominatedBy("C")).isEmpty()
    assertThat(dom.dominatedBy("D")).isEmpty()
    assertThat(dom.dominatedBy("E")).isEmpty()
    assertThat(dom.dominatedBy("F")).isEmpty()
  }

  @Test
  fun virtualRootNameCollision() {
    // Test that virtual root generation handles name collisions
    val graph = buildGraph {
      putEdge("___VIRTUAL_ROOT___", "A")
      putEdge("A", "B")
    }
    val dom = Dominators(graph)

    // Should still work correctly despite having a node named like the virtual root
    assertThat(dom.nodes()).containsExactly("___VIRTUAL_ROOT___", "A", "B")
    assertThat(dom.dominatedBy("___VIRTUAL_ROOT___")).containsExactly("A", "B")
  }

  private fun buildGraph(block: MutableGraph<String>.() -> Unit): Graph<String> {
    val graph = GraphBuilder.directed().allowsSelfLoops(false).build<String>()
    graph.block()
    return graph
  }
}
