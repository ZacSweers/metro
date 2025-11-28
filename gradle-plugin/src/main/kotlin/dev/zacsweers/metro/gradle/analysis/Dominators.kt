// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.autonomousapps.graph.DominanceTree
import com.google.common.graph.Graph
import com.google.common.graph.GraphBuilder

/**
 * Computes dominator relationships in a directed acyclic graph (DAG).
 *
 * A node X dominates node Y if every path from a root to Y must pass through X. Nodes that dominate
 * many others are critical bottlenecks in the graph.
 *
 * This implementation handles graphs with multiple roots by creating a virtual root that connects
 * to all natural roots, then filtering it from results.
 *
 * @param N the node type
 * @see <a href="http://www.hipersoft.rice.edu/grads/publications/dom14.pdf">A Simple, Fast
 *   Dominance Algorithm</a>
 */
internal class Dominators<N : Any>(private val graph: Graph<N>) {

  private val virtualRoot: N? by lazy {
    if (graph.nodes().isEmpty()) null else generateVirtualRoot()
  }

  private val dominanceGraph: Graph<N> by lazy { computeDominanceGraph() }

  private val descendantsCache: MutableMap<N, Set<N>> = mutableMapOf()

  /** All nodes in the original graph (excluding the virtual root). */
  fun nodes(): Set<N> = graph.nodes()

  /**
   * Returns all nodes transitively dominated by [node].
   *
   * A node Y is dominated by X if every path from a root to Y passes through X. This returns the
   * transitive closure of the dominance relationship.
   *
   * @return the set of dominated nodes, or empty set if [node] is not in the graph
   */
  fun dominatedBy(node: N): Set<N> {
    if (node !in graph.nodes()) return emptySet()
    return descendantsCache.getOrPut(node) { computeDescendants(node) }
  }

  /**
   * Returns the immediate dominator of [node], or null if [node] is a root or not in the graph.
   *
   * The immediate dominator is the closest dominator - the last node that must be visited on every
   * path from a root to [node].
   */
  fun immediateDominatorOf(node: N): N? {
    if (node !in graph.nodes()) return null
    val predecessors = dominanceGraph.predecessors(node)
    return predecessors.firstOrNull { it != virtualRoot }
  }

  /**
   * Returns all nodes sorted by the number of nodes they dominate (descending).
   *
   * This is useful for identifying bottleneck nodes that are critical paths in the graph.
   */
  fun nodesByDominatedCount(): List<Pair<N, Int>> {
    return graph
      .nodes()
      .map { node -> node to dominatedBy(node).size }
      .sortedByDescending { it.second }
  }

  @Suppress("UNCHECKED_CAST")
  private fun generateVirtualRoot(): N {
    // For String graphs, generate a unique virtual root name
    // For other types, we need the graph to not contain our sentinel
    val nodes = graph.nodes()
    if (nodes.isEmpty()) error("Cannot generate virtual root for empty graph")

    val sample = nodes.first()
    return when (sample) {
      is String -> {
        var candidate = "___VIRTUAL_ROOT___"
        var i = 0
        @Suppress("UNCHECKED_CAST") val stringNodes = nodes as Set<String>
        while (candidate in stringNodes) {
          i++
          candidate = "___VIRTUAL_ROOT___$i"
        }
        @Suppress("UNCHECKED_CAST")
        candidate as N
      }
      else -> error("Dominators only supports String node types currently")
    }
  }

  private fun computeDominanceGraph(): Graph<N> {
    val nodes = graph.nodes()
    if (nodes.isEmpty()) {
      return GraphBuilder.directed().build<N>()
    }

    val graphWithVirtualRoot = GraphBuilder.directed().allowsSelfLoops(false).build<N>()

    // Copy all edges
    for (e in graph.edges()) {
      graphWithVirtualRoot.putEdge(e.source(), e.target())
    }

    // Add virtual root with edges to all natural roots
    val roots = nodes.filter { graph.inDegree(it) == 0 }.ifEmpty { nodes }
    val vRoot = virtualRoot!!
    for (r in roots) {
      graphWithVirtualRoot.putEdge(vRoot, r)
    }

    return DominanceTree(graphWithVirtualRoot, vRoot).dominanceGraph
  }

  private fun computeDescendants(node: N): Set<N> {
    val result = mutableSetOf<N>()
    val vRoot = virtualRoot

    fun visit(n: N) {
      for (child in dominanceGraph.successors(n)) {
        if (child != vRoot && result.add(child)) {
          visit(child)
        }
      }
    }

    visit(node)
    return result
  }
}
