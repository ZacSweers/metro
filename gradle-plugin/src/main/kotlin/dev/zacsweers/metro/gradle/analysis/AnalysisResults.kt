// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import kotlinx.serialization.Serializable

/** Statistics about a dependency graph. */
@Serializable
public data class GraphStatistics(
  val totalBindings: Int,
  val scopedBindings: Int,
  val unscopedBindings: Int,
  val bindingsByKind: Map<String, Int>,
  val averageDependencies: Double,
  val maxDependencies: Int,
  val maxDependenciesBinding: String?,
  val rootBindings: Int,
  val leafBindings: Int,
  val multibindingCount: Int,
  val aliasCount: Int,
)

/** Result of longest path analysis. */
@Serializable
public data class LongestPathResult(
  val longestPathLength: Int,
  val longestPaths: List<List<String>>,
  val averagePathLength: Double,
  val pathLengthDistribution: Map<Int, Int>,
)

/** Result of dominator analysis. */
@Serializable public data class DominatorResult(val dominators: List<DominatorNode>)

/** A node in the dominator tree with its dominated nodes. */
@Serializable
public data class DominatorNode(
  val key: String,
  val bindingKind: String,
  val dominatedCount: Int,
  val dominatedKeys: List<String>,
)

/** Result of betweenness centrality analysis. */
@Serializable public data class CentralityResult(val centralityScores: List<CentralityScore>)

/** Centrality score for a single binding. */
@Serializable
public data class CentralityScore(
  val key: String,
  val bindingKind: String,
  val betweennessCentrality: Double,
  val normalizedCentrality: Double,
)

/** Result of fan-in/fan-out analysis. */
@Serializable
public data class FanAnalysisResult(
  val bindings: List<FanScore>,
  val highFanIn: List<FanScore>,
  val highFanOut: List<FanScore>,
  val averageFanIn: Double,
  val averageFanOut: Double,
)

/** Fan-in and fan-out scores for a single binding. */
@Serializable
public data class FanScore(
  val key: String,
  val bindingKind: String,
  val fanIn: Int,
  val fanOut: Int,
  val dependents: List<String>,
  val dependencies: List<String>,
)

/** Result of paths-to-root analysis. Contains shortest paths from each node to the graph root. */
@Serializable
public data class PathsToRootResult(
  /** The graph root node key. */
  val rootKey: String,
  /** Map from node key to its shortest path to root (list of keys from node to root inclusive). */
  val paths: Map<String, List<String>>,
)

/** Complete analysis for a single dependency graph. */
@Serializable
public data class GraphAnalysis(
  val graphName: String,
  val statistics: GraphStatistics,
  val longestPath: LongestPathResult,
  val dominator: DominatorResult,
  val centrality: CentralityResult,
  val fanAnalysis: FanAnalysisResult,
  val pathsToRoot: PathsToRootResult = PathsToRootResult("", emptyMap()),
)

/** Combined analysis report for all graphs in a project. */
@Serializable
public data class FullAnalysisReport(val projectPath: String, val graphs: List<GraphAnalysis>) {
  /** Number of graphs in this report. */
  val graphCount: Int
    get() = graphs.size
}
