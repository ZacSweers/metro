// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.autonomousapps.graph.ShortestPath
import com.google.common.graph.Graph

/** Performs various graph analysis algorithms on a [BindingGraph] using Guava graphs. */
public class GraphAnalyzer(private val bindingGraph: BindingGraph) {

  private val fullGraph: Graph<String>
    get() = bindingGraph.graph

  private val eagerGraph: Graph<String>
    get() = bindingGraph.eagerGraph

  /** Compute basic statistics about the graph. */
  public fun computeStatistics(): GraphStatistics {
    val keys = bindingGraph.keys
    val fanOuts = keys.map { bindingGraph.fanOut(it) }
    val bindings = bindingGraph.getAllBindings().toList()
    val bindingsByKind = bindings.groupingBy { it.bindingKind }.eachCount().toSortedMap()

    return GraphStatistics(
      totalBindings = bindingGraph.size,
      scopedBindings = bindings.count { it.isScoped },
      unscopedBindings = bindings.count { !it.isScoped },
      bindingsByKind = bindingsByKind,
      averageDependencies = if (fanOuts.isNotEmpty()) fanOuts.average() else 0.0,
      maxDependencies = fanOuts.maxOrNull() ?: 0,
      maxDependenciesBinding = keys.maxByOrNull { bindingGraph.fanOut(it) },
      rootBindings = bindingGraph.findRoots().size,
      leafBindings = bindingGraph.findLeaves().size,
      multibindingCount = bindings.count { it.multibinding != null },
      aliasCount = bindings.count { it.aliasTarget != null },
    )
  }

  public fun findLongestPaths(maxPaths: Int = 5): LongestPathResult {
    val vs = eagerGraph.nodes()
    if (vs.isEmpty()) {
      return LongestPathResult(0, emptyList(), 0.0, sortedMapOf())
    }

    val lp = LongestPath(eagerGraph)
    val roots = eagerGraph.computeRoots(vs)
    val rootLens = roots.map { lp.lengthFrom(it) ?: 1 }
    val distribution = rootLens.groupingBy { it }.eachCount().toSortedMap()

    return LongestPathResult(
      longestPathLength = lp.longestPathLength,
      longestPaths = lp.paths(maxPaths),
      averagePathLength = if (rootLens.isNotEmpty()) rootLens.average() else 0.0,
      pathLengthDistribution = distribution,
    )
  }

  /**
   * Computes dominator relationships in the graph. A node X dominates node Y if every path from a
   * root to Y must pass through X. Nodes that dominate many others are critical bottlenecks.
   *
   * @see <a href="http://www.hipersoft.rice.edu/grads/publications/dom14.pdf">A Simple, Fast
   *   Dominance Algorithm.</a>
   */
  public fun computeDominators(): DominatorResult {
    if (fullGraph.nodes().isEmpty()) {
      return DominatorResult(emptyList())
    }

    val dominators = Dominators(fullGraph)
    val result =
      dominators
        .nodes()
        .map { key ->
          val dominated = dominators.dominatedBy(key)
          DominatorNode(
            key = key,
            bindingKind = bindingGraph.getBinding(key)?.bindingKind ?: "Unknown",
            dominatedCount = dominated.size,
            dominatedKeys = dominated.sorted(),
          )
        }
        .sortedByDescending { it.dominatedCount }

    return DominatorResult(result)
  }

  /**
   * Computes betweenness centrality for each node. Nodes with high centrality lie on many shortest
   * paths between other nodes, making them important connectors in the graph.
   */
  public fun computeBetweennessCentrality(): CentralityResult {
    if (fullGraph.nodes().isEmpty()) {
      return CentralityResult(emptyList())
    }

    val centrality = Centrality(fullGraph)
    val scores =
      centrality.nodesByScore().map { (key, score) ->
        CentralityScore(
          key = key,
          bindingKind = bindingGraph.getBinding(key)?.bindingKind ?: "Unknown",
          betweennessCentrality = score,
          normalizedCentrality = centrality.normalizedScoreOf(key) ?: 0.0,
        )
      }

    return CentralityResult(scores)
  }

  /**
   * Computes shortest paths from all nodes to the graph root using Dijkstra's algorithm.
   *
   * Paths are stored as lists from each node to the root (inclusive).
   */
  public fun computePathsToRoot(): PathsToRootResult {
    val graphRoot = bindingGraph.graphRoot
    if (graphRoot == null || fullGraph.nodes().isEmpty()) {
      return PathsToRootResult("", emptyMap())
    }

    val shortestPath = ShortestPath(fullGraph, graphRoot)
    val paths = mutableMapOf<String, List<String>>()

    for (node in fullGraph.nodes()) {
      if (shortestPath.hasPathTo(node)) {
        // pathTo returns path from root to node, we want node to root so reverse it
        paths[node] = shortestPath.pathTo(node).toList().reversed()
      } else {
        paths[node] = emptyList()
      }
    }

    return PathsToRootResult(graphRoot, paths)
  }

  /**
   * Computes fan-in (number of dependents) and fan-out (number of dependencies) for each binding.
   * High fan-in indicates widely used bindings; high fan-out indicates bindings with many
   * dependencies.
   */
  public fun computeFanAnalysis(topN: Int): FanAnalysisResult {
    if (fullGraph.nodes().isEmpty()) {
      return FanAnalysisResult(emptyList(), emptyList(), emptyList(), 0.0, 0.0)
    }

    val fanAnalysis = FanAnalysis(fullGraph)

    val base =
      fanAnalysis.nodes().map { key ->
        FanScore(
          key = key,
          bindingKind = bindingGraph.getBinding(key)?.bindingKind ?: "Unknown",
          fanIn = fanAnalysis.fanIn(key) ?: 0,
          fanOut = fanAnalysis.fanOut(key) ?: 0,
          dependents = emptyList(),
          dependencies = emptyList(),
        )
      }

    fun hydrate(s: FanScore) =
      s.copy(
        dependents = fanAnalysis.predecessors(s.key).map { it }.sorted(),
        dependencies = fanAnalysis.successors(s.key).map { it }.sorted(),
      )

    val topIn = base.sortedByDescending { it.fanIn }.take(topN).map(::hydrate)
    val topOut = base.sortedByDescending { it.fanOut }.take(topN).map(::hydrate)

    return FanAnalysisResult(
      bindings = base.sortedBy { it.key },
      highFanIn = topIn,
      highFanOut = topOut,
      averageFanIn = fanAnalysis.averageFanIn,
      averageFanOut = fanAnalysis.averageFanOut,
    )
  }
}
