// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph

/**
 * Computes longest paths in a directed acyclic graph (DAG).
 *
 * Algorithm based on https://en.wikipedia.org/wiki/Longest_path_problem#Acyclic_graphs
 *
 * For each vertex v in a given DAG, the length of the longest path starting at v is computed by:
 * 1. Finding a topological ordering of the DAG.
 * 2. Processing vertices in reverse topological order, computing the longest path from each vertex
 *    by looking at its successors and adding one to the maximum length recorded for those
 *    successors.
 *
 * The running time is O(V + E) where V is the number of vertices and E is the number of edges.
 *
 * @param N the node type
 */
internal class LongestPath<N : Any>(private val graph: Graph<N>) {

  private val lengths: Map<N, Int> by lazy { computeLengths() }
  private val roots: Set<N> by lazy {
    graph.nodes().filterTo(mutableSetOf()) { graph.inDegree(it) == 0 }
  }

  /** The length of the longest path in the graph, or 0 if the graph is empty. */
  val longestPathLength: Int by lazy { roots.maxOfOrNull { lengths[it] ?: 1 } ?: 0 }

  /**
   * Returns the longest path length starting from [node], or null if [node] is not in the graph.
   */
  fun lengthFrom(node: N): Int? = lengths[node]

  /**
   * Returns up to [maxPaths] longest paths in the graph.
   *
   * Paths are reconstructed by starting from roots with the maximum path length and following edges
   * to successors that maintain the optimal path length.
   */
  fun paths(maxPaths: Int = 5): List<List<N>> {
    if (graph.nodes().isEmpty() || maxPaths <= 0) return emptyList()

    val result = mutableListOf<List<N>>()
    val successorCache = mutableMapOf<N, List<N>>()

    fun sortedSuccessors(node: N): List<N> =
      successorCache.getOrPut(node) {
        graph.successors(node).sortedWith(compareBy { it.toString() })
      }

    fun build(node: N, path: ArrayDeque<N>) {
      if (result.size >= maxPaths) return
      path.add(node)

      val nodeLen = lengths[node] ?: 1
      val needed = nodeLen - 1
      val successors = sortedSuccessors(node)
      val bestSuccessors =
        if (needed >= 1) {
          successors.filter { (lengths[it] ?: 1) == needed }
        } else {
          emptyList()
        }

      if (bestSuccessors.isEmpty()) {
        result += path.toList()
      } else {
        for (successor in bestSuccessors) {
          build(successor, path)
          if (result.size >= maxPaths) break
        }
      }

      path.removeLast()
    }

    // Start from roots with maximum path length
    for (root in roots.sortedWith(compareBy { it.toString() })) {
      if ((lengths[root] ?: 1) == longestPathLength) {
        build(root, ArrayDeque())
      }
      if (result.size >= maxPaths) break
    }

    return result.distinctBy { it.joinToString("->") }
  }

  private fun computeLengths(): Map<N, Int> {
    val nodes = graph.nodes()
    if (nodes.isEmpty()) return emptyMap()

    val topo = graph.topologicalSort()
    if (topo.isEmpty()) return nodes.associateWith { 1 }

    val result = HashMap<N, Int>(nodes.size)
    for (v in topo.asReversed()) {
      var best = 1
      for (w in graph.successors(v)) {
        best = maxOf(best, 1 + (result[w] ?: 1))
      }
      result[v] = best
    }
    return result
  }
}
