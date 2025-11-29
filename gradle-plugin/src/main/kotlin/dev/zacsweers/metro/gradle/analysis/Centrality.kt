// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph

/**
 * Computes betweenness centrality for nodes in a directed graph.
 *
 * Betweenness centrality measures how often a node appears on shortest paths between other nodes.
 * Nodes with high centrality are important connectors - they lie on many shortest paths and
 * removing them would significantly impact graph connectivity.
 *
 * The algorithm is based on Brandes (2001) "A faster algorithm for betweenness centrality" and runs
 * in O(VE) time for unweighted graphs.
 *
 * @param N the node type
 * @see <a href="https://en.wikipedia.org/wiki/Betweenness_centrality">Betweenness centrality</a>
 */
internal class Centrality<N : Any>(graph: Graph<N>) {

  private val scores: Map<N, Double> by lazy {
    if (graph.nodes().isEmpty()) {
      emptyMap()
    } else {
      BetweennessCentrality(graph).scores
    }
  }

  private val maxScore: Double by lazy { scores.values.maxOrNull() ?: 0.0 }

  /** All nodes in the graph. */
  fun nodes(): Set<N> = scores.keys

  /**
   * Returns the raw betweenness centrality score for [node].
   *
   * Higher values indicate the node lies on more shortest paths between other nodes.
   *
   * @return the score, or null if [node] is not in the graph
   */
  fun scoreOf(node: N): Double? = scores[node]

  /**
   * Returns the normalized betweenness centrality score for [node], scaled to [0, 1].
   *
   * A score of 1.0 means this node has the highest centrality in the graph.
   *
   * @return the normalized score, or null if [node] is not in the graph
   */
  fun normalizedScoreOf(node: N): Double? {
    val score = scores[node] ?: return null
    return if (maxScore > 0) score / maxScore else 0.0
  }

  /**
   * Returns all nodes sorted by their betweenness centrality score (descending).
   *
   * The first element has the highest centrality.
   */
  fun nodesByScore(): List<Pair<N, Double>> =
    scores.entries.map { it.key to it.value }.sortedByDescending { it.second }

  /**
   * Returns all nodes with their normalized scores, sorted by score (descending).
   *
   * Scores are normalized to [0, 1] where 1.0 is the highest centrality in the graph.
   */
  fun nodesByNormalizedScore(): List<Pair<N, Double>> =
    scores.entries
      .map { it.key to (if (maxScore > 0) it.value / maxScore else 0.0) }
      .sortedByDescending { it.second }
}
