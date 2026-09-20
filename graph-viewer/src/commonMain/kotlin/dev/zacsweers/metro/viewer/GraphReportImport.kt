// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.viewer

import dev.zacsweers.metro.gradle.analysis.FanScore
import dev.zacsweers.metro.gradle.analysis.FullAnalysisReport
import dev.zacsweers.metro.gradle.analysis.GraphAnalysis
import dev.zacsweers.metro.gradle.analysis.GraphMetadata
import dev.zacsweers.metro.gradle.analysis.GraphReportRenderer
import dev.zacsweers.metro.gradle.analysis.analysisEdges
import dev.zacsweers.metro.gradle.analysis.bindingType
import dev.zacsweers.metro.gradle.analysis.extractDisplayName
import dev.zacsweers.metro.gradle.analysis.typeDisplayNames
import dev.zacsweers.metro.gradle.analysis.unwrapTypeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** A report selected through the browser's file picker or drop area. */
@Serializable public data class ReportFile(val name: String, val content: String)

/** The graphs and warnings available before opening a report. */
@Serializable
public data class ReportSummary(
  val graphs: List<ImportedGraph>,
  val warnings: List<String>,
  val hasAnalysis: Boolean,
)

/** One graph available in an import. */
@Serializable
public data class ImportedGraph(val name: String, val label: String, val bindingCount: Int)

/** Validates imported reports and prepares the same data used by generated HTML reports. */
public class GraphReportImport(files: List<ReportFile>) {
  private val json = Json { ignoreUnknownKeys = true }
  private val graphDefinitions = linkedMapOf<String, JsonObject>()
  private val graphs = linkedMapOf<String, GraphMetadata>()
  private val analyses = linkedMapOf<String, GraphAnalysis>()
  private val projectPaths = mutableSetOf<String>()
  private val analysisProjects = mutableSetOf<String>()
  private val warnings = mutableListOf<String>()
  private val renderer: GraphReportRenderer

  public val summary: ReportSummary

  init {
    require(files.isNotEmpty()) { "Choose at least one compiler report or graphMetadata.json." }
    files.forEach(::readFile)
    require(graphs.isNotEmpty()) {
      "No graph metadata was found. Add compiler graph reports or graphMetadata.json alongside analysis.json."
    }
    validateParents()
    validateAnalysis()
    findMissingReports()
    renderer =
      GraphReportRenderer(FullAnalysisReport("", analyses.values.toList()), graphs.values.toList())
    val typeNames = typeDisplayNames(graphs.values.map { it.graphType ?: it.graph })
    val displayNames =
      graphs.values.associate { graph ->
        graph.graph to extractDisplayName(graph.graphType ?: graph.graph, typeNames)
      }
    val repeatedNames =
      displayNames.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    summary =
      ReportSummary(
        graphs.values
          .sortedByDescending { it.bindings.size }
          .map { graph ->
            val name = displayNames.getValue(graph.graph)
            val label =
              if (name in repeatedNames) {
                graph.graph
              } else {
                name
              }
            ImportedGraph(graph.graph, label, graph.bindings.size)
          },
        warnings.toList(),
        analyses.isNotEmpty(),
      )
  }

  /** Converts one imported graph with all available related reports. */
  public fun renderGraph(name: String): JsonObject {
    val graph = requireNotNull(graphs[name]) { "No report was imported for graph $name." }
    return renderer.buildData(graph)
  }

  private fun readFile(file: ReportFile) {
    try {
      val document = json.parseToJsonElement(file.content) as? JsonObject
      requireNotNull(document) { "Expected a JSON object." }
      validateVersion(document)
      if (document["graph"] is JsonPrimitive) {
        readGraph(document)
        return
      }
      val reports = document["graphs"] as? JsonArray
      requireNotNull(reports) {
        "Expected a compiler graph report, graphMetadata.json, or analysis.json."
      }
      require(reports.isNotEmpty()) { "This report contains no graphs." }
      val objects = reports.map {
        requireNotNull(it as? JsonObject) { "Each graph must be a JSON object." }
      }
      val isMetadata = objects.all { it["graph"] is JsonPrimitive }
      val isAnalysis = objects.all { it["graphName"] is JsonPrimitive }
      val project = (document["projectPath"] as? JsonPrimitive)?.content
      when {
        isMetadata -> {
          val countValue = document["graphCount"]
          if (countValue != null) {
            val count = countValue as? JsonPrimitive
            require(count != null && !count.isString && count.intOrNull == reports.size) {
              "graphCount must be an integer matching the number of graphs."
            }
          }
          project?.let(projectPaths::add)
          objects.forEach(::readGraph)
        }
        isAnalysis -> {
          project?.let(analysisProjects::add)
          objects.forEach(::readAnalysis)
        }
        else -> error("The graphs array mixes unsupported report types.")
      }
    } catch (failure: IllegalArgumentException) {
      throw IllegalArgumentException("${file.name}: ${failure.message}", failure)
    } catch (failure: IllegalStateException) {
      throw IllegalArgumentException("${file.name}: ${failure.message}", failure)
    }
  }

  private fun validateVersion(document: JsonObject) {
    val version = document["formatVersion"] ?: return
    val number = version as? JsonPrimitive
    require(number != null && !number.isString && number.intOrNull == 1) {
      "Unsupported report format version $version. Use the viewer for your Metro version."
    }
  }

  private fun readGraph(document: JsonObject) {
    validateVersion(document)
    val graph = json.decodeFromJsonElement<GraphMetadata>(document)
    require(graph.graph.isNotBlank()) { "The graph name is empty." }
    val parentGraph = graph.parentGraph
    require(parentGraph == null || parentGraph.isNotBlank()) {
      "The parent graph name is empty."
    }
    val referencedKeys = buildList {
      addAll(graph.includedGraphKeys)
      graph.roots?.accessors?.forEach { add(it.key) }
      graph.roots?.injectors?.forEach { add(it.key) }
      graph.bindings.forEach { binding ->
        binding.dependencies.forEach { add(it.key) }
        binding.assistedTarget?.let { target ->
          add(target.key)
          target.dependencies.forEach { add(it.key) }
        }
      }
    }
    require(referencedKeys.none { it.isBlank() }) {
      "${graph.graph} contains an empty root or dependency key."
    }
    require(graph.bindings.all { it.key.isNotBlank() && it.bindingKind.isNotBlank() }) {
      "${graph.graph} contains a binding without a key or kind."
    }
    val identities = graph.bindings.map { it.key to (it.bindingKind == "MembersInjected") }
    require(identities.distinct().size == identities.size) {
      "${graph.graph} contains duplicate binding definitions."
    }
    val previous = graphDefinitions[graph.graph]
    require(previous == null || previous == document) {
      "Conflicting reports for ${graph.graph}. Choose reports from a single compilation."
    }
    graphDefinitions[graph.graph] = document
    graphs[graph.graph] = graph
  }

  private fun readAnalysis(document: JsonObject) {
    validateVersion(document)
    val analysis = json.decodeFromJsonElement<GraphAnalysis>(document)
    val previous = analyses[analysis.graphName]
    require(previous == null || previous == analysis) {
      "Conflicting analysis for ${analysis.graphName}. Choose one analysis report."
    }
    analyses[analysis.graphName] = analysis
  }

  private fun validateParents() {
    for (graph in graphs.values) {
      val visited = mutableSetOf(graph.graph)
      var parent = graph.parentGraph
      while (parent != null) {
        require(visited.add(parent)) { "The parent graph reports contain a cycle at $parent." }
        parent = graphs[parent]?.parentGraph
      }
    }
  }

  private fun validateAnalysis() {
    if (projectPaths.isNotEmpty()) {
      require(analysisProjects.all { it in projectPaths }) {
        "The analysis project does not match the imported graph metadata."
      }
    }
    val unmatched = analyses.keys - graphs.keys
    require(analyses.isEmpty() || unmatched.size < analyses.size) {
      "The analysis file has no graphs matching the imported metadata."
    }
    for (name in unmatched) {
      analyses.remove(name)
      warnings += "Analysis for $name was skipped because its graph report wasn't imported."
    }
    for (analysis in analyses.values) {
      val graph =
        requireNotNull(graphs[analysis.graphName]) {
          "Analysis references ${analysis.graphName}. Add its graph metadata or omit the analysis file."
        }
      val keys = graph.bindings.mapTo(mutableSetOf()) { it.key }
      val measuredKeys = analysis.fanAnalysis.bindings.mapTo(mutableSetOf()) { it.key }
      require(keys - graph.graph == measuredKeys - graph.graph) {
        "Analysis bindings do not match ${graph.graph}. Generate metadata and analysis from the same compilation."
      }
      keys += graph.graph
      val referencedKeys = buildList {
        analysis.fanAnalysis.bindings.forEach {
          addAll(it.dependencies)
          addAll(it.dependents)
        }
        analysis.longestPath.longestPaths.forEach(::addAll)
        addAll(analysis.centrality.centralityScores.map { it.key })
        analysis.dominator.dominators.forEach {
          add(it.key)
          addAll(it.dominatedKeys)
        }
        addAll(analysis.pathsToRoot.paths.keys)
        analysis.pathsToRoot.paths.values.forEach(::addAll)
      }
      require(referencedKeys.all { it in keys }) {
        "Analysis references bindings absent from ${graph.graph}. Generate both reports from the same compilation."
      }
      validateAnalysisEdges(graph, analysis)
    }
  }

  private fun validateAnalysisEdges(graph: GraphMetadata, analysis: GraphAnalysis) {
    val edges = graph.analysisEdges()
    val dependencies = edges.groupBy({ it.source }, { it.target })
    val dependents = edges.groupBy({ it.target }, { it.source })
    fun validateBinding(binding: FanScore, hasConnections: Boolean) {
      val expectedDependencies = dependencies[binding.key].orEmpty().toSet()
      val expectedDependents = dependents[binding.key].orEmpty().toSet()
      val matchesDependencies = binding.fanOut == expectedDependencies.size
      val matchesDependents = binding.fanIn == expectedDependents.size
      require(matchesDependencies && matchesDependents) {
        "Analysis dependencies do not match ${graph.graph}. Generate metadata and analysis from the same compilation."
      }
      if (hasConnections) {
        require(
          binding.dependencies.toSet() == expectedDependencies &&
            binding.dependents.toSet() == expectedDependents
        ) {
          "Analysis dependencies do not match ${graph.graph}. Generate metadata and analysis from the same compilation."
        }
      }
    }
    for (binding in analysis.fanAnalysis.bindings) {
      validateBinding(binding, hasConnections = false)
    }
    for (binding in analysis.fanAnalysis.highFanIn + analysis.fanAnalysis.highFanOut) {
      validateBinding(binding, hasConnections = true)
    }
    val eagerEdges = edges.filter { it.eager }.mapTo(mutableSetOf()) { it.source to it.target }
    for (path in analysis.longestPath.longestPaths) {
      require(path.zipWithNext().all { it in eagerEdges }) {
        "The longest chain in the analysis does not match ${graph.graph}. Generate both reports from the same compilation."
      }
    }
  }

  private fun findMissingReports() {
    for (graph in graphs.values) {
      val parent = graph.parentGraph
      if (parent != null && parent !in graphs) {
        warnings +=
          "${graphLabel(graph)}: add the report for parent graph $parent to see inherited bindings in their owning graph."
      }
      val extensionTypes = buildSet {
        graph.bindings.mapNotNullTo(this) { it.extensionType }
        graph.extensions?.accessors?.forEach { add(bindingType(unwrapTypeKey(it.key))) }
        graph.bindings
          .filter { it.bindingKind == "GraphExtension" }
          .forEach { add(bindingType(unwrapTypeKey(it.key))) }
      }
      for (type in extensionTypes) {
        val available =
          graphs.values.any {
            val matchesType = it.graphType == type || it.graph == type
            val matchesParent = it.parentGraph == graph.graph
            matchesType && matchesParent
          }
        if (!available) {
          warnings += "${graphLabel(graph)}: extension report $type is missing."
        }
      }
      for (key in graph.includedGraphKeys) {
        val type = bindingType(key)
        val available =
          graphs.values.any {
            val matchesType = it.graph == type || it.graphType == type
            matchesType && it.parentGraph == null
          }
        if (!available) {
          warnings += "${graphLabel(graph)}: included graph report $key is missing."
        }
      }
    }
  }

  private fun graphLabel(graph: GraphMetadata): String {
    return (graph.graphType ?: graph.graph).substringAfterLast('.')
  }
}
