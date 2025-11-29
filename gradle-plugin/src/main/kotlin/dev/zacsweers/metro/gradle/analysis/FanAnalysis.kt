// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph

/**
 * Computes fan-in and fan-out metrics for nodes in a directed graph.
 * - **Fan-in**: The number of incoming edges (predecessors/dependents). High fan-in indicates a
 *   widely used node.
 * - **Fan-out**: The number of outgoing edges (successors/dependencies). High fan-out indicates a
 *   node with many dependencies.
 *
 * @param N the node type
 */
internal class FanAnalysis<N : Any>(private val graph: Graph<N>) {

  /** All nodes in the graph. */
  fun nodes(): Set<N> = graph.nodes()

  /**
   * Returns the fan-in (number of predecessors) for [node].
   *
   * @return the fan-in count, or null if [node] is not in the graph
   */
  fun fanIn(node: N): Int? = if (node in graph.nodes()) graph.inDegree(node) else null

  /**
   * Returns the fan-out (number of successors) for [node].
   *
   * @return the fan-out count, or null if [node] is not in the graph
   */
  fun fanOut(node: N): Int? = if (node in graph.nodes()) graph.outDegree(node) else null

  /**
   * Returns the predecessors (nodes with edges pointing to [node]).
   *
   * @return the set of predecessors, or empty set if [node] is not in the graph
   */
  fun predecessors(node: N): Set<N> =
    if (node in graph.nodes()) graph.predecessors(node) else emptySet()

  /**
   * Returns the successors (nodes that [node] has edges pointing to).
   *
   * @return the set of successors, or empty set if [node] is not in the graph
   */
  fun successors(node: N): Set<N> =
    if (node in graph.nodes()) graph.successors(node) else emptySet()

  /**
   * Returns all nodes sorted by fan-in (descending).
   *
   * The first element has the highest fan-in (most dependents).
   */
  fun nodesByFanIn(): List<Pair<N, Int>> =
    graph.nodes().map { it to graph.inDegree(it) }.sortedByDescending { it.second }

  /**
   * Returns all nodes sorted by fan-out (descending).
   *
   * The first element has the highest fan-out (most dependencies).
   */
  fun nodesByFanOut(): List<Pair<N, Int>> =
    graph.nodes().map { it to graph.outDegree(it) }.sortedByDescending { it.second }

  /** The average fan-in across all nodes, or 0.0 if the graph is empty. */
  val averageFanIn: Double by lazy {
    val nodes = graph.nodes()
    if (nodes.isEmpty()) 0.0 else nodes.sumOf { graph.inDegree(it) }.toDouble() / nodes.size
  }

  /** The average fan-out across all nodes, or 0.0 if the graph is empty. */
  val averageFanOut: Double by lazy {
    val nodes = graph.nodes()
    if (nodes.isEmpty()) 0.0 else nodes.sumOf { graph.outDegree(it) }.toDouble() / nodes.size
  }

  /** The maximum fan-in in the graph, or 0 if the graph is empty. */
  val maxFanIn: Int by lazy { graph.nodes().maxOfOrNull { graph.inDegree(it) } ?: 0 }

  /** The maximum fan-out in the graph, or 0 if the graph is empty. */
  val maxFanOut: Int by lazy { graph.nodes().maxOfOrNull { graph.outDegree(it) } ?: 0 }
}
