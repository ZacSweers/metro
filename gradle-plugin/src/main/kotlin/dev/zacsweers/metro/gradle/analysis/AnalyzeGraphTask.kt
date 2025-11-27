// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import kotlin.io.path.bufferedWriter
import kotlin.io.path.createParentDirectories
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Analyzes Metro dependency graphs and produces a comprehensive analysis report.
 *
 * This task consumes the aggregated graph metadata JSON produced by [GenerateGraphMetadataTask] and
 * runs various graph analysis algorithms including:
 * - Basic statistics (binding counts, scoping, etc.)
 * - Longest path analysis (critical dependency chains)
 * - Dominator analysis (critical dependencies)
 * - Betweenness centrality (bridge nodes)
 * - Fan-in/fan-out analysis (coupling metrics)
 *
 * The output is a comprehensive JSON report useful for CI validation, automated analysis, and
 * identifying potential issues in dependency graph structure.
 */
@CacheableTask
public abstract class AnalyzeGraphTask : DefaultTask() {

  /** The aggregated graph metadata JSON file to analyze. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val inputFile: RegularFileProperty

  /** Maximum number of longest paths to include in the report. */
  @get:Input public abstract val maxLongestPaths: Property<Int>

  /** Number of top fan-in/fan-out bindings to highlight. */
  @get:Input public abstract val topFanCount: Property<Int>

  /** The output file for the analysis report. */
  @get:OutputFile public abstract val outputFile: RegularFileProperty

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
  }

  init {
    group = "metro"
    description = "Analyzes Metro dependency graphs and produces a comprehensive report"
    maxLongestPaths.convention(5)
    topFanCount.convention(10)
  }

  @TaskAction
  public fun analyze() {
    val input = inputFile.get().asFile
    val output = outputFile.get().asFile.toPath()

    logger.lifecycle("Analyzing Metro graph metadata from ${input.absolutePath}")

    val metadata = json.decodeFromString<AggregatedGraphMetadata>(input.readText())

    val statistics = mutableListOf<GraphStatistics>()
    val longestPaths = mutableListOf<LongestPathResult>()
    val dominators = mutableListOf<DominatorResult>()
    val centrality = mutableListOf<CentralityResult>()
    val fanAnalysis = mutableListOf<FanAnalysisResult>()

    for (graphMetadata in metadata.graphs) {
      logger.lifecycle("Analyzing graph: ${graphMetadata.graph}")

      val graph = BindingGraph.from(graphMetadata)
      val analyzer = GraphAnalyzer(graph)

      statistics.add(analyzer.computeStatistics())
      longestPaths.add(analyzer.findLongestPaths(maxLongestPaths.get()))
      dominators.add(analyzer.computeDominators())
      centrality.add(analyzer.computeBetweennessCentrality())
      fanAnalysis.add(analyzer.computeFanAnalysis(topFanCount.get()))
    }

    val report =
      FullAnalysisReport(
        projectPath = metadata.projectPath,
        graphCount = metadata.graphCount,
        statistics = statistics,
        longestPaths = longestPaths,
        dominators = dominators,
        centrality = centrality,
        fanAnalysis = fanAnalysis,
      )

    output.createParentDirectories()
    output.bufferedWriter().use { writer -> writer.write(json.encodeToString(report)) }

    logger.lifecycle("Analysis report written to $output")
    logSummary(report)
  }

  private fun logSummary(report: FullAnalysisReport) {
    logger.lifecycle("")
    logger.lifecycle("=== Metro Graph Analysis Summary ===")
    logger.lifecycle("Project: ${report.projectPath}")
    logger.lifecycle("Graphs analyzed: ${report.graphCount}")

    for (stats in report.statistics) {
      logger.lifecycle("")
      logger.lifecycle("Graph: ${stats.graphName}")
      logger.lifecycle("  Total bindings: ${stats.totalBindings}")
      logger.lifecycle("  Scoped: ${stats.scopedBindings}, Unscoped: ${stats.unscopedBindings}")
      logger.lifecycle("  Avg dependencies: ${"%.2f".format(stats.averageDependencies)}")
      logger.lifecycle("  Binding types: ${stats.bindingsByKind}")
    }

    for (path in report.longestPaths) {
      if (path.longestPathLength > 0) {
        logger.lifecycle("")
        logger.lifecycle("Longest path in ${path.graphName}: ${path.longestPathLength} nodes")
        path.longestPaths.firstOrNull()?.let { p ->
          logger.lifecycle("  ${p.joinToString(" -> ") { it.substringAfterLast('.') }}")
        }
      }
    }

    for (fan in report.fanAnalysis) {
      val topFanIn = fan.highFanIn.firstOrNull()
      if (topFanIn != null && topFanIn.fanIn > 0) {
        logger.lifecycle("")
        logger.lifecycle("Highest fan-in in ${fan.graphName}:")
        logger.lifecycle("  ${topFanIn.key.substringAfterLast('.')} (${topFanIn.fanIn} dependents)")
      }
    }
  }

  internal companion object {
    const val NAME = "analyzeMetroGraph"
  }
}
