// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph
import com.google.common.graph.GraphBuilder
import com.google.common.graph.MutableGraph
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FanAnalysisTest {

  @Test
  fun emptyGraph() {
    val graph = buildGraph {}
    val fan = FanAnalysis(graph)

    assertThat(fan.nodes()).isEmpty()
    assertThat(fan.nodesByFanIn()).isEmpty()
    assertThat(fan.nodesByFanOut()).isEmpty()
    assertThat(fan.averageFanIn).isEqualTo(0.0)
    assertThat(fan.averageFanOut).isEqualTo(0.0)
    assertThat(fan.maxFanIn).isEqualTo(0)
    assertThat(fan.maxFanOut).isEqualTo(0)
  }

  @Test
  fun singleNode() {
    val graph = buildGraph { addNode("A") }
    val fan = FanAnalysis(graph)

    assertThat(fan.nodes()).containsExactly("A")
    assertThat(fan.fanIn("A")).isEqualTo(0)
    assertThat(fan.fanOut("A")).isEqualTo(0)
    assertThat(fan.predecessors("A")).isEmpty()
    assertThat(fan.successors("A")).isEmpty()
  }

  @Test
  fun simpleChain() {
    // A -> B -> C -> D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("C", "D")
    }
    val fan = FanAnalysis(graph)

    // Fan-in (predecessors)
    assertThat(fan.fanIn("A")).isEqualTo(0)
    assertThat(fan.fanIn("B")).isEqualTo(1)
    assertThat(fan.fanIn("C")).isEqualTo(1)
    assertThat(fan.fanIn("D")).isEqualTo(1)

    // Fan-out (successors)
    assertThat(fan.fanOut("A")).isEqualTo(1)
    assertThat(fan.fanOut("B")).isEqualTo(1)
    assertThat(fan.fanOut("C")).isEqualTo(1)
    assertThat(fan.fanOut("D")).isEqualTo(0)

    // Predecessors
    assertThat(fan.predecessors("A")).isEmpty()
    assertThat(fan.predecessors("B")).containsExactly("A")
    assertThat(fan.predecessors("C")).containsExactly("B")
    assertThat(fan.predecessors("D")).containsExactly("C")

    // Successors
    assertThat(fan.successors("A")).containsExactly("B")
    assertThat(fan.successors("B")).containsExactly("C")
    assertThat(fan.successors("C")).containsExactly("D")
    assertThat(fan.successors("D")).isEmpty()
  }

  @Test
  fun highFanIn() {
    // Multiple nodes pointing to D
    //   A
    //   |
    //   v
    // B->D<-C
    val graph = buildGraph {
      putEdge("A", "D")
      putEdge("B", "D")
      putEdge("C", "D")
    }
    val fan = FanAnalysis(graph)

    assertThat(fan.fanIn("D")).isEqualTo(3)
    assertThat(fan.predecessors("D")).containsExactly("A", "B", "C")
    assertThat(fan.maxFanIn).isEqualTo(3)

    // D should be first in nodesByFanIn
    val byFanIn = fan.nodesByFanIn()
    assertThat(byFanIn.first().first).isEqualTo("D")
    assertThat(byFanIn.first().second).isEqualTo(3)
  }

  @Test
  fun highFanOut() {
    // A points to multiple nodes
    // A -> B
    // A -> C
    // A -> D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
    }
    val fan = FanAnalysis(graph)

    assertThat(fan.fanOut("A")).isEqualTo(3)
    assertThat(fan.successors("A")).containsExactly("B", "C", "D")
    assertThat(fan.maxFanOut).isEqualTo(3)

    // A should be first in nodesByFanOut
    val byFanOut = fan.nodesByFanOut()
    assertThat(byFanOut.first().first).isEqualTo("A")
    assertThat(byFanOut.first().second).isEqualTo(3)
  }

  @Test
  fun averages() {
    // A -> B -> C
    //      |
    //      v
    //      D
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("B", "C")
      putEdge("B", "D")
    }
    val fan = FanAnalysis(graph)

    // Fan-in: A=0, B=1, C=1, D=1 -> average = 3/4 = 0.75
    assertThat(fan.averageFanIn).isEqualTo(0.75)

    // Fan-out: A=1, B=2, C=0, D=0 -> average = 3/4 = 0.75
    assertThat(fan.averageFanOut).isEqualTo(0.75)
  }

  @Test
  fun nodeNotInGraph() {
    val graph = buildGraph { putEdge("A", "B") }
    val fan = FanAnalysis(graph)

    assertThat(fan.fanIn("Z")).isNull()
    assertThat(fan.fanOut("Z")).isNull()
    assertThat(fan.predecessors("Z")).isEmpty()
    assertThat(fan.successors("Z")).isEmpty()
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
    val fan = FanAnalysis(graph)

    // A: fan-in=0, fan-out=2
    assertThat(fan.fanIn("A")).isEqualTo(0)
    assertThat(fan.fanOut("A")).isEqualTo(2)

    // B: fan-in=1, fan-out=1
    assertThat(fan.fanIn("B")).isEqualTo(1)
    assertThat(fan.fanOut("B")).isEqualTo(1)

    // C: fan-in=1, fan-out=1
    assertThat(fan.fanIn("C")).isEqualTo(1)
    assertThat(fan.fanOut("C")).isEqualTo(1)

    // D: fan-in=2, fan-out=0
    assertThat(fan.fanIn("D")).isEqualTo(2)
    assertThat(fan.fanOut("D")).isEqualTo(0)

    assertThat(fan.maxFanIn).isEqualTo(2)
    assertThat(fan.maxFanOut).isEqualTo(2)
  }

  @Test
  fun nodesByFanInIsSortedDescending() {
    val graph = buildGraph {
      putEdge("A", "D")
      putEdge("B", "D")
      putEdge("C", "D")
      putEdge("A", "C")
    }
    val fan = FanAnalysis(graph)

    val byFanIn = fan.nodesByFanIn()
    for (i in 0 until byFanIn.size - 1) {
      assertThat(byFanIn[i].second).isAtLeast(byFanIn[i + 1].second)
    }
  }

  @Test
  fun nodesByFanOutIsSortedDescending() {
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
      putEdge("B", "D")
    }
    val fan = FanAnalysis(graph)

    val byFanOut = fan.nodesByFanOut()
    for (i in 0 until byFanOut.size - 1) {
      assertThat(byFanOut[i].second).isAtLeast(byFanOut[i + 1].second)
    }
  }

  @Test
  fun disconnectedComponents() {
    // A -> B (component 1)
    // C -> D (component 2)
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("C", "D")
    }
    val fan = FanAnalysis(graph)

    assertThat(fan.fanOut("A")).isEqualTo(1)
    assertThat(fan.fanIn("B")).isEqualTo(1)
    assertThat(fan.fanOut("C")).isEqualTo(1)
    assertThat(fan.fanIn("D")).isEqualTo(1)

    // Average: total edges = 2, nodes = 4
    // Fan-in: 0+1+0+1 = 2, avg = 0.5
    // Fan-out: 1+0+1+0 = 2, avg = 0.5
    assertThat(fan.averageFanIn).isEqualTo(0.5)
    assertThat(fan.averageFanOut).isEqualTo(0.5)
  }

  @Test
  fun complexGraph() {
    //       A
    //      /|\
    //     B C D
    //     |/| |
    //     E F G
    //      \|/
    //       H
    val graph = buildGraph {
      putEdge("A", "B")
      putEdge("A", "C")
      putEdge("A", "D")
      putEdge("B", "E")
      putEdge("C", "E")
      putEdge("C", "F")
      putEdge("D", "G")
      putEdge("E", "H")
      putEdge("F", "H")
      putEdge("G", "H")
    }
    val fan = FanAnalysis(graph)

    // A has highest fan-out (3)
    assertThat(fan.fanOut("A")).isEqualTo(3)
    assertThat(fan.maxFanOut).isEqualTo(3)

    // H has highest fan-in (3)
    assertThat(fan.fanIn("H")).isEqualTo(3)
    assertThat(fan.maxFanIn).isEqualTo(3)

    // E has fan-in of 2 (from B and C)
    assertThat(fan.fanIn("E")).isEqualTo(2)
    assertThat(fan.predecessors("E")).containsExactly("B", "C")
  }

  private fun buildGraph(block: MutableGraph<String>.() -> Unit): Graph<String> {
    val graph = GraphBuilder.directed().allowsSelfLoops(false).build<String>()
    graph.block()
    return graph
  }
}
