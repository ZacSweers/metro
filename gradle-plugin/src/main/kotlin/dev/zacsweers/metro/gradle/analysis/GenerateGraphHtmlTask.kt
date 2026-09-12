// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus
import dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationOutcome
import dev.zacsweers.metro.compiler.graph.reporting.graphReportFileName
import dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates self-contained HTML explorers for Metro dependency graphs. */
@ExperimentalMetroGradleApi
@CacheableTask
public abstract class GenerateGraphHtmlTask : DefaultTask() {

  /** The aggregated graph metadata JSON file to visualize. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val inputFile: RegularFileProperty

  /**
   * Analysis report JSON file from [AnalyzeGraphTask]. Analysis metrics (fan-in/out, centrality,
   * dominator count) are included in the visualization.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val analysisFile: RegularFileProperty

  /** The output directory for HTML files (one per graph). */
  @get:OutputDirectory public abstract val outputDirectory: DirectoryProperty

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
  }

  init {
    group = "metro"
    description = "Generates interactive HTML visualizations of Metro dependency graphs"
  }

  @TaskAction
  internal fun generate() {
    val input = inputFile.get().asFile
    val outputDir = outputDirectory.get().asFile

    logger.lifecycle("Generating Metro graph visualizations from file://${input.absolutePath}")

    val metadata = json.decodeFromString<AggregatedGraphMetadata>(input.readText())

    // Parse analysis report
    val analysisInput = analysisFile.get().asFile
    logger.lifecycle("Including analysis data from file://${analysisInput.absolutePath}")
    val analysisReport = json.decodeFromString<FullAnalysisReport>(analysisInput.readText())

    // Build per-graph analysis lookup
    val renderer = GraphHtmlRenderer(analysisReport, metadata.graphs)

    outputDir.mkdirs()

    for (graphMetadata in metadata.graphs) {
      val htmlContent = renderer.generateHtml(graphMetadata)

      val fileName = graphReportFileName(graphMetadata.graph, "html")
      val outputFile = File(outputDir, fileName)
      outputFile.toPath().createParentDirectories()
      outputFile.toPath().writeText(htmlContent)

      logger.lifecycle("Generated file://${outputFile.absolutePath}")
    }

    // Generate index page
    val indexContent = renderer.generateIndex(metadata)
    val indexFile = File(outputDir, "index.html")
    indexFile.toPath().writeText(indexContent)
    logger.lifecycle("Generated file://${indexFile.absolutePath}")
  }

  internal companion object {
    const val NAME = "generateMetroGraphHtml"
  }
}

@OptIn(ExperimentalMetroGradleApi::class)
internal class GraphHtmlRenderer(
  report: FullAnalysisReport = FullAnalysisReport("", emptyList()),
  private val graphs: List<GraphMetadata> = emptyList(),
) {
  private val json = Json { encodeDefaults = true }
  private val analysisLookup = buildAnalysisLookup(report)

  /** Builds a lookup map from graph name to per-binding analysis metrics. */
  private fun buildAnalysisLookup(report: FullAnalysisReport): Map<String, GraphAnalysisData> {
    val result = mutableMapOf<String, GraphAnalysisData>()

    for (graph in report.graphs) {
      // Build per-binding metrics map
      val bindingMetrics = mutableMapOf<String, BindingAnalysisMetrics>()

      // Fan-in/Fan-out
      graph.fanAnalysis.bindings.forEach { fan ->
        bindingMetrics
          .getOrPut(fan.key) { BindingAnalysisMetrics() }
          .apply {
            fanIn = fan.fanIn
            fanOut = fan.fanOut
          }
      }

      // Centrality
      graph.centrality.centralityScores.forEach { score ->
        bindingMetrics
          .getOrPut(score.key) { BindingAnalysisMetrics() }
          .apply { betweennessCentrality = score.normalizedCentrality }
      }

      // Dominator count
      graph.dominator.dominators.forEach { dom ->
        bindingMetrics
          .getOrPut(dom.key) { BindingAnalysisMetrics() }
          .apply { dominatorCount = dom.dominatedCount }
      }

      result[graph.graphName] =
        GraphAnalysisData(
          bindingMetrics = bindingMetrics,
          pathsToRoot = graph.pathsToRoot.paths,
          longestPath = graph.longestPath.longestPaths.firstOrNull().orEmpty(),
        )
    }

    return result
  }

  /** Analysis data for a single graph. */
  private data class GraphAnalysisData(
    val bindingMetrics: Map<String, BindingAnalysisMetrics>,
    val pathsToRoot: Map<String, List<String>> = emptyMap(),
    val longestPath: List<String> = emptyList(),
  )

  /** Analysis metrics for a single binding. */
  private data class BindingAnalysisMetrics(
    var fanIn: Int = 0,
    var fanOut: Int = 0,
    var betweennessCentrality: Double = 0.0,
    var dominatorCount: Int = 0,
  )

  fun generateIndex(metadata: AggregatedGraphMetadata): String {
    val graphs =
      metadata.graphs.sortedWith(
        compareByDescending<GraphMetadata> { it.bindings.size }.thenBy { it.graph }
      )
    val typeNames = typeDisplayNames(graphs.map { it.graph })
    val rows =
      graphs.joinToString("\n") { graph ->
        val fileName = graphReportFileName(graph.graph, "html")
        val displayName = extractDisplayName(graph.graph, typeNames)
        """<a class="graph-card" href="${escapeHtml(reportUrl(fileName))}"><span class="line-badge">M</span><span class="graph-title" title="${escapeHtml(graph.graph)}">${escapeHtml(displayName)}<small>${graph.bindings.count { it.isScoped }} scoped · ${graph.roots?.accessors?.size ?: 0} roots</small></span><span class="graph-size">${graph.bindings.size}<small>bindings</small></span><span aria-hidden="true">↗</span></a>"""
      }
    return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Metro Graphs · ${escapeHtml(metadata.projectPath)}</title>
<style>
* { box-sizing: border-box; }
body { margin: 0; background: #080c11; color: #edf3f8; font: 15px system-ui, sans-serif; }
main { max-width: 1040px; margin: 0 auto; padding: 80px 32px; }
.brand { display: flex; align-items: center; gap: 12px; font-weight: 700; letter-spacing: .16em; font-size: 13px; }
.routes { display: flex; height: 5px; margin: 32px 0 56px; gap: 5px; }
.routes i { flex: 1; background: #0078c6; border-radius: 3px; }
.routes i:nth-child(2) { background: #d82233; }.routes i:nth-child(3) { background: #f6bc26; }.routes i:nth-child(4) { background: #009952; }
h1 { font-size: clamp(30px, 5vw, 52px); letter-spacing: -.05em; margin: 0 0 14px; font-weight: 650; }
p { color: #8d9baa; line-height: 1.6; overflow-wrap: anywhere; }
.count { margin: 36px 0 12px; font-size: 12px; text-transform: uppercase; letter-spacing: .12em; }
.graph-card { display: flex; align-items: center; gap: 20px; padding: 24px 0; color: inherit; text-decoration: none; border-bottom: 1px solid #26313d; }
.graph-card:hover, .graph-card:focus-visible { background: #101b27; outline: 2px solid #0078c6; outline-offset: 6px; }
.line-badge { display: inline-grid; place-items: center; width: 32px; height: 32px; flex-shrink: 0; border-radius: 50%; background: #0078c6; color: white; font-weight: 800; }
.graph-title { flex: 1; overflow-wrap: anywhere; font-weight: 600; }.graph-size { text-align: right; font: 22px ui-monospace, monospace; }
small { display: block; color: #8d9baa; margin-top: 8px; font: 12px system-ui, sans-serif; }
</style>
</head>
<body><main>
<div class="brand"><span class="line-badge">M</span> METRO / GRAPH EXPLORER</div>
<div class="routes" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
<h1>Dependency graphs</h1>
<p>${escapeHtml(metadata.projectPath)}<br>Explore bindings, follow dependencies, and trace the route from a root.</p>
<p class="count">${metadata.graphs.size} dependency graphs</p>
$rows
</main></body></html>
"""
      .trimIndent()
  }

  fun buildData(metadata: GraphMetadata): JsonObject {
    val analysis = analysisLookup[metadata.graph] ?: GraphAnalysisData(emptyMap())
    val graph = buildRegions(metadata)
    val canonicalIds = graph.getValue("canonicalIds").jsonObject
    fun canonicalId(id: String) = canonicalIds[id]?.jsonPrimitive?.content ?: id
    val pathsToRoot =
      analysis.pathsToRoot.entries.associate { (key, path) ->
        canonicalId(key) to path.map { canonicalId(it) }
      }
    return buildJsonObject {
      put("graphName", JsonPrimitive(metadata.graph))
      put("typeNames", graph.getValue("typeNames"))
      put("nodes", graph.getValue("nodes"))
      put("links", graph.getValue("links"))
      put("regions", graph.getValue("regions"))
      graph["initialRegionId"]?.let { put("initialRegionId", it) }
      put("categories", getBindingCategories())
      put("pathsToRoot", json.encodeToJsonElement(pathsToRoot))
      put("longestPath", json.encodeToJsonElement(analysis.longestPath.map { canonicalId(it) }))
      put("bindingExplanations", json.encodeToJsonElement(metadata.bindingExplanations))
      put("scopes", json.encodeToJsonElement(metadata.scopes))
      put("stats", json.encodeToJsonElement(metadata.stats))
      put("config", json.encodeToJsonElement(metadata.config))
    }
  }

  private fun buildRegions(metadata: GraphMetadata): JsonObject = RegionBuilder(metadata).build()

  private data class IncludedGraph(
    val consumer: GraphMetadata,
    val input: BindingMetadata,
    val producer: GraphMetadata,
    val getters: List<BindingMetadata>,
  )

  private data class RegionSelection(
    val root: GraphMetadata,
    val included: List<GraphMetadata>,
    val dependencyRegions: Set<String>,
    val includedGraphs: List<IncludedGraph>,
  )

  private data class BindingReference(
    val ownerId: String,
    val graph: GraphMetadata,
    val binding: BindingMetadata,
    val included: Boolean = false,
  )

  private data class RegionContents(
    val graph: GraphMetadata,
    val id: String,
    val data: JsonObject,
    val ownedNodes: List<JsonObject>,
    val parent: GraphMetadata?,
    val creators: List<BindingMetadata>,
  )

  private inner class RegionBuilder(private val metadata: GraphMetadata) {
    private val reports = (graphs + metadata).distinct()
    private val reportsByName = reports.groupBy { it.graph }
    private val selection = selectRegions()
    private val regionRoot = selection.root
    private val included = selection.included
    private val dependencyRegions = selection.dependencyRegions
    private val includedGraphs = selection.includedGraphs
    private val seen = included.mapTo(mutableSetOf()) { it.graph }
    private val metadataByName = included.associateBy { it.graph }
    private val data = included.associate { graph ->
      val analysis = analysisLookup[graph.graph] ?: GraphAnalysisData(emptyMap())
      graph.graph to buildGraphData(graph, analysis)
    }
    private val bindingReferences = mutableMapOf<String, BindingReference>()
    private val includedAccessors = mutableMapOf<String, MutableSet<String>>()
    private val dependencyRegionByInput = mutableMapOf<String, String>()
    private val referencePaths: Map<String, List<BindingReference>>
    private val referencesByOwner: Map<String, List<BindingReference>>

    init {
      recordInheritedReferences()
      recordIncludedReferences()
      referencePaths = buildReferencePaths()
      referencesByOwner = bindingReferences.entries.groupBy({ canonicalId(it.key) }, { it.value })
    }

    fun build(): JsonObject {
      val nodes = mutableListOf<JsonObject>()
      val links = mutableListOf<JsonObject>()
      val regions = mutableListOf<JsonObject>()
      val typeNames = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
      for (graph in included) {
        val region = regionContents(graph)
        nodes += buildRegionNodes(region)
        links += buildRegionLinks(region)
        links += buildIncludedLinks(region.id)
        links += buildExtensionLinks(region)
        regions += buildRegionMetadata(region)
        typeNames.putAll(region.data.getValue("typeNames").jsonObject)
      }
      return buildJsonObject {
        put("nodes", JsonArray(nodes))
        put("links", JsonArray(links))
        put("regions", JsonArray(regions))
        put("typeNames", JsonObject(typeNames))
        put(
          "canonicalIds",
          buildJsonObject {
            for (node in data.getValue(metadata.graph).getValue("nodes").jsonArray) {
              val id = node.jsonObject.getValue("id").jsonPrimitive.content
              put(id, JsonPrimitive(canonicalId(id)))
            }
          },
        )
        if (regionRoot !== metadata) {
          put("initialRegionId", JsonPrimitive("graph:${metadata.graph}"))
        }
      }
    }

    private fun selectRegions(): RegionSelection {
      val ancestors = mutableListOf(metadata)
      val ancestorNames = mutableSetOf(metadata.graph)
      var parentName = metadata.parentGraph
      var cyclicAncestors = false
      while (parentName != null) {
        if (!ancestorNames.add(parentName)) {
          cyclicAncestors = true
          break
        }
        val parent = reportsByName[parentName]?.singleOrNull() ?: break
        ancestors.add(parent)
        parentName = parent.parentGraph
      }
      val regionRoot =
        if (cyclicAncestors) {
          metadata
        } else {
          ancestors.last()
        }
      val includedGraphs = findIncludedGraphs()
      val included = mutableListOf(regionRoot)
      val seen = mutableSetOf(regionRoot.graph)
      val dependencyRegions = mutableSetOf<String>()
      for (index in 0 until (reports.size + 1)) {
        if (cyclicAncestors) {
          break
        }
        val parent = included.getOrNull(index) ?: break
        for (candidate in reports.filter { it.parentGraph == parent.graph }) {
          val child =
            if (candidate.graph == metadata.graph) {
              metadata
            } else {
              reportsByName[candidate.graph]?.singleOrNull() ?: continue
            }
          if (seen.add(child.graph)) {
            included.add(child)
          }
        }
        for (dependency in includedGraphs.filter { it.consumer.graph == parent.graph }) {
          if (seen.add(dependency.producer.graph)) {
            included.add(dependency.producer)
            dependencyRegions.add(dependency.producer.graph)
          }
        }
      }
      return RegionSelection(regionRoot, included, dependencyRegions, includedGraphs)
    }

    private fun findIncludedGraphs(): List<IncludedGraph> {
      val suppliedInputs = reports.flatMap { graph ->
        graph.bindings
          .filter {
            val suppliedInstance = it.bindingKind == "BoundInstance" && it.isGraphInput == true
            suppliedInstance && it.key in graph.includedGraphKeys
          }
          .map { graph to it }
      }
      val includedGraphs = mutableListOf<IncludedGraph>()
      for ((consumer, input) in suppliedInputs) {
        val inputType = bindingType(input.key)
        val suppliedInstances = suppliedInputs.filter { bindingType(it.second.key) == inputType }
        if (suppliedInstances.size != 1) {
          continue
        }
        val producer =
          reports.singleOrNull {
            it.parentGraph == null && (it.graph == inputType || it.graphType == inputType)
          } ?: continue
        if (producer.graph == consumer.graph) {
          continue
        }
        val getters =
          consumer.bindings.filter { binding ->
            val dependency = binding.graphDependency ?: return@filter false
            if (binding.bindingKind != "GraphDependency" || dependency.fromParent) {
              return@filter false
            }
            val matchesOwner =
              dependency.ownerKey == input.key && dependency.ownerGraph == inputType
            val recordedOwner = binding.dependencies.any { it.key == input.key }
            if (!matchesOwner || !recordedOwner) {
              return@filter false
            }
            val accessor =
              producer.roots?.accessors.orEmpty().singleOrNull {
                val matchesDeclaration = it.name != null && it.name == binding.declaration
                matchesDeclaration && unwrapTypeKey(it.key) == binding.key
              } ?: return@filter false
            producer.bindings.any { it.key == unwrapTypeKey(accessor.key) }
          }
        if (getters.isNotEmpty()) {
          includedGraphs += IncludedGraph(consumer, input, producer, getters)
        }
      }
      return includedGraphs
    }

    private fun nodeId(graph: GraphMetadata, id: String): String {
      if (graph === metadata || id == "graph:${graph.graph}") {
        return id
      }
      return "region:${graph.graph}:$id"
    }

    private fun ancestor(graph: GraphMetadata, name: String?): GraphMetadata? {
      if (name == null) {
        return null
      }
      var parent = graph.parentGraph
      val visited = mutableSetOf<String>()
      while (parent != null && visited.add(parent)) {
        val candidate = metadataByName[parent] ?: return null
        if (candidate.graph == name || candidate.graphType == name) {
          return candidate
        }
        parent = candidate.parentGraph
      }
      return null
    }

    private fun capturedOwner(
      graph: GraphMetadata,
      key: String,
    ): Pair<GraphMetadata, List<BindingMetadata>>? {
      val path = mutableListOf<BindingMetadata>()
      val visited = mutableSetOf<String>()
      var current = key
      while (visited.add(current)) {
        val binding = graph.bindings.singleOrNull { it.key == current } ?: return null
        path += binding
        val aliasTarget = binding.aliasTarget
        if (binding.bindingKind == "Alias" && aliasTarget != null) {
          if (binding.dependencies.none { it.key == aliasTarget }) {
            return null
          }
          current = aliasTarget
          continue
        }
        if (binding.bindingKind != "BoundInstance" || binding.isGraphInput != false) {
          return null
        }
        val owner = ancestor(graph, binding.key) ?: return null
        val ownsInstance =
          owner.bindings.any {
            it.key == binding.key && it.bindingKind == "BoundInstance" && it.isGraphInput == false
          }
        if (ownsInstance) {
          return owner to path
        }
        return null
      }
      return null
    }

    private fun recordInheritedReferences(): Unit {
      for (graph in included) {
        for (binding in graph.bindings) {
          val dependency = binding.graphDependency ?: continue
          if (!dependency.fromParent) {
            continue
          }
          val owner = ancestor(graph, dependency.ownerGraph) ?: continue
          val recordedOwner = binding.dependencies.any { it.key == dependency.ownerKey }
          val ownerHasBinding = owner.bindings.any { it.key == binding.key }
          if (recordedOwner && ownerHasBinding) {
            bindingReferences[nodeId(graph, binding.key)] =
              BindingReference(nodeId(owner, binding.key), graph, binding)
            capturedOwner(graph, dependency.ownerKey)?.let { (instanceOwner, path) ->
              val instanceId = nodeId(instanceOwner, path.last().key)
              for (capture in path) {
                bindingReferences[nodeId(graph, capture.key)] =
                  BindingReference(instanceId, graph, capture)
              }
            }
          }
        }
      }
    }

    private fun recordIncludedReferences(): Unit {
      for (dependency in includedGraphs) {
        if (dependency.consumer.graph !in seen || dependency.producer.graph !in dependencyRegions) {
          continue
        }
        val inputId = nodeId(dependency.consumer, dependency.input.key)
        val producerNodes =
          data.getValue(dependency.producer.graph).getValue("nodes").jsonArray.map { it.jsonObject }
        for (getter in dependency.getters) {
          val accessor =
            producerNodes.singleOrNull {
              val namedAccessor =
                it["rootKind"] == JsonPrimitive("accessor") &&
                  it["declaration"] == JsonPrimitive(getter.declaration)
              namedAccessor &&
                unwrapTypeKey(it.getValue("fullKey").jsonPrimitive.content) == getter.key
            } ?: continue
          val accessorId =
            nodeId(dependency.producer, accessor.getValue("id").jsonPrimitive.content)
          bindingReferences[nodeId(dependency.consumer, getter.key)] =
            BindingReference(accessorId, dependency.consumer, getter, included = true)
          includedAccessors.getOrPut(inputId) { mutableSetOf() }.add(accessorId)
          dependencyRegionByInput[inputId] = "graph:${dependency.producer.graph}"
        }
      }
    }

    private fun buildReferencePaths(): Map<String, List<BindingReference>> {
      return bindingReferences.keys.associateWith { id ->
        val path = mutableListOf<BindingReference>()
        val visited = mutableSetOf<String>()
        var current = id
        while (visited.add(current)) {
          val reference = bindingReferences[current] ?: break
          path += reference
          current = reference.ownerId
        }
        path
      }
    }

    private fun canonicalId(id: String): String = referencePaths[id]?.lastOrNull()?.ownerId ?: id

    private fun referenceData(reference: BindingReference, includeBinding: Boolean): JsonObject =
      buildJsonObject {
        put("regionId", JsonPrimitive("graph:${reference.graph.graph}"))
        put("graphName", JsonPrimitive(reference.graph.graph))
        if (includeBinding) {
          put("binding", json.encodeToJsonElement(reference.binding))
        } else {
          put("key", JsonPrimitive(reference.binding.key))
        }
      }

    private fun regionContents(graph: GraphMetadata): RegionContents {
      val regionId = "graph:${graph.graph}"
      val graphData = data.getValue(graph.graph)
      val originalNodes = graphData.getValue("nodes").jsonArray.map { it.jsonObject }
      val ownedNodes = originalNodes.filter {
        nodeId(graph, it.getValue("id").jsonPrimitive.content) !in bindingReferences
      }
      val parent =
        if (graph === regionRoot) {
          null
        } else {
          metadataByName[graph.parentGraph]
        }
      val creators =
        parent?.bindings.orEmpty().filter { binding ->
          val isExtension =
            binding.bindingKind == "GraphExtension" ||
              binding.bindingKind == "GraphExtensionFactory"
          isExtension && (binding.extensionType ?: binding.key) == graph.graphType
        }
      return RegionContents(graph, regionId, graphData, ownedNodes, parent, creators)
    }

    private fun buildRegionNodes(region: RegionContents): List<JsonObject> {
      val nodes = mutableListOf<JsonObject>()
      val graph = region.graph
      val regionId = region.id
      val ownedNodes = region.ownedNodes
      for (node in ownedNodes) {
        val id = nodeId(graph, node.getValue("id").jsonPrimitive.content)
        nodes +=
          JsonObject(
            node.toMutableMap().apply {
              put("id", JsonPrimitive(id))
              put("regionId", JsonPrimitive(regionId))
              put("graphName", JsonPrimitive(graph.graph))
              if (node["rootOwner"] != null) {
                put("rootOwner", JsonPrimitive(regionId))
              }
              dependencyRegionByInput[id]?.let { put("dependencyRegionId", JsonPrimitive(it)) }
              referencesByOwner[id]?.let { references ->
                for ((field, matching) in
                  listOf(
                    "inheritedBindings" to references.filterNot { it.included },
                    "includedBindings" to references.filter { it.included },
                  )) {
                  if (matching.isNotEmpty()) {
                    put(field, JsonArray(matching.map { referenceData(it, includeBinding = true) }))
                  }
                }
              }
            }
          )
      }
      return nodes
    }

    private fun buildRegionLinks(region: RegionContents): List<JsonObject> {
      val links = mutableListOf<JsonObject>()
      val graph = region.graph
      val regionId = region.id
      val graphData = region.data
      for (link in graphData.getValue("links").jsonArray.map { it.jsonObject }) {
        val source = nodeId(graph, link.getValue("source").jsonPrimitive.content)
        val target = nodeId(graph, link.getValue("target").jsonPrimitive.content)
        if (source in bindingReferences) {
          continue
        }
        links +=
          JsonObject(
            link.toMutableMap().apply {
              put("source", JsonPrimitive(source))
              put("target", JsonPrimitive(canonicalId(target)))
              put("regionId", JsonPrimitive(regionId))
              referencePaths[target]?.let { path ->
                val inherited = path.filterNot { it.included }
                if (inherited.isNotEmpty()) {
                  put("parentDependency", JsonPrimitive(true))
                  put(
                    "inheritedVia",
                    JsonArray(inherited.map { referenceData(it, includeBinding = false) }),
                  )
                }
                val supplied = path.filter { it.included }
                if (supplied.isNotEmpty()) {
                  put(
                    "includedVia",
                    JsonArray(supplied.map { referenceData(it, includeBinding = false) }),
                  )
                }
              }
            }
          )
      }
      return links
    }

    private fun buildIncludedLinks(regionId: String): List<JsonObject> {
      val links = mutableListOf<JsonObject>()

      for ((input, accessors) in includedAccessors) {
        if (dependencyRegionByInput[input] == regionId) {
          for (accessor in accessors) {
            links += buildJsonObject {
              put("source", JsonPrimitive(input))
              put("target", JsonPrimitive(accessor))
              put("edgeType", JsonPrimitive("includes"))
              put("includedInstance", JsonPrimitive(true))
              put(
                "lineStyle",
                buildJsonObject { put("color", JsonPrimitive(Colors.GRAPH_DEPENDENCY)) },
              )
              val references = referencesByOwner[accessor].orEmpty().filter { it.included }
              put(
                "includedVia",
                JsonArray(references.map { referenceData(it, includeBinding = false) }),
              )
            }
          }
        }
      }
      return links
    }

    private fun buildExtensionLinks(region: RegionContents): List<JsonObject> {
      val links = mutableListOf<JsonObject>()
      val parent = region.parent
      val creators = region.creators
      val regionId = region.id
      if (parent != null) {
        for (creator in creators) {
          links += buildJsonObject {
            put("source", JsonPrimitive(nodeId(parent, creator.key)))
            put("target", JsonPrimitive(regionId))
            put("edgeType", JsonPrimitive("extension"))
            put("rootMembership", JsonPrimitive(true))
            put("containment", JsonPrimitive(true))
          }
        }
      }
      return links
    }

    private fun buildRegionMetadata(region: RegionContents): JsonObject {
      val graph = region.graph
      val regionId = region.id
      val parent = region.parent
      val creators = region.creators
      val ownedNodes = region.ownedNodes
      return buildJsonObject {
        put("id", JsonPrimitive(regionId))
        put("ownerId", JsonPrimitive(regionId))
        put("graphName", JsonPrimitive(graph.graph))
        put("name", JsonPrimitive(extractDisplayName(graph.graphType ?: graph.graph)))
        val kind =
          if (graph.graph in dependencyRegions) {
            "dependency"
          } else if (parent == null) {
            "graph"
          } else {
            "extension"
          }
        put("kind", JsonPrimitive(kind))
        put("parentId", json.encodeToJsonElement(parent?.let { "graph:${it.graph}" }))
        put(
          "viaNodeId",
          json.encodeToJsonElement(creators.firstOrNull()?.let { nodeId(parent!!, it.key) }),
        )
        put(
          "nodeIds",
          json.encodeToJsonElement(
            ownedNodes.map { nodeId(graph, it.getValue("id").jsonPrimitive.content) }
          ),
        )
        put(
          "rootIds",
          json.encodeToJsonElement(
            ownedNodes
              .filter { it["isRootMember"]?.jsonPrimitive?.booleanOrNull == true }
              .map { nodeId(graph, it.getValue("id").jsonPrimitive.content) }
          ),
        )
        put(
          "inputIds",
          json.encodeToJsonElement(
            ownedNodes
              .filter { it["isGraphInput"]?.jsonPrimitive?.booleanOrNull == true }
              .map { nodeId(graph, it.getValue("id").jsonPrimitive.content) }
          ),
        )
        put("scopes", json.encodeToJsonElement(graph.scopes))
        put("bindingExplanations", json.encodeToJsonElement(graph.bindingExplanations))
      }
    }
  }

  fun generateHtml(metadata: GraphMetadata): String {
    val data = buildData(metadata)
    val replacements =
      mapOf(
        "__METRO_TITLE__" to escapeHtml(metadata.graph),
        "__METRO_INDEX_URL__" to
          "../".repeat(graphReportFileName(metadata.graph, "html").count { it == '/' }) +
            "index.html",
        "__METRO_ICON_URL__" to
          "data:image/svg+xml;base64," +
            Base64.getEncoder()
              .encodeToString(resource("pluginIcon_dark.svg").toByteArray(StandardCharsets.UTF_8)),
        "__METRO_STYLE__" to resource("graph-viewer.css"),
        "__METRO_SCRIPT__" to resource("graph-viewer.js"),
        "__METRO_DATA__" to
          data.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026"),
      )
    return Regex("__METRO_[A-Z_]+__").replace(resource("graph-viewer.html")) { match ->
      replacements.getValue(match.value)
    }
  }

  private fun resource(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/dev/zacsweers/metro/gradle/analysis/$name")) {
        "Missing graph viewer resource: $name"
      }
      .bufferedReader()
      .use { it.readText() }

  private fun reportUrl(fileName: String): String =
    fileName.split('/').joinToString("/") { part ->
      URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
    }

  private fun escapeHtml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  private fun JsonObjectBuilder.bindingDetails(binding: BindingMetadata) {
    put("declaration", JsonPrimitive(binding.declaration.orEmpty()))
    put("nameHint", JsonPrimitive(binding.nameHint))
    put("rawDependencies", json.encodeToJsonElement(binding.dependencies))
    put("aliasTarget", json.encodeToJsonElement(binding.aliasTarget))
    put("multibinding", json.encodeToJsonElement(binding.multibinding))
    put("optionalWrapper", json.encodeToJsonElement(binding.optionalWrapper))
    put("graphDependency", json.encodeToJsonElement(binding.graphDependency))
    put("extensionType", json.encodeToJsonElement(binding.extensionType))
  }

  private fun buildGraphData(metadata: GraphMetadata, analysis: GraphAnalysisData): JsonObject =
    GraphDataBuilder(metadata, analysis).build()

  private data class DefaultValueInfo(
    val syntheticKey: String,
    val targetType: String,
    val consumerKey: String,
    val targetPackage: String,
  )

  private data class RootMemberInfo(
    val id: String,
    val key: String,
    val kind: String,
    val name: String? = null,
    val isProperty: Boolean = false,
    val resolvedKey: String? = null,
    val binding: BindingMetadata? = null,
    val isDeferrable: Boolean = false,
    val wrapperType: String? = null,
    val isInherited: Boolean = false,
    val declaringGraph: String? = null,
    val declaringType: String? = null,
    val origin: String? = null,
  )

  private data class GlowThresholds(
    val highCentrality: Double,
    val mediumCentrality: Double,
    val dominatorCount: Double,
    val fanIn: Double,
  )

  private inner class GraphDataBuilder(
    private val metadata: GraphMetadata,
    private val analysis: GraphAnalysisData,
  ) {

    private val categoryMap =
      mapOf(
        "ConstructorInjected" to 0,
        "Provided" to 1,
        "Alias" to 2,
        "BoundInstance" to 3,
        "Multibinding" to 4,
        "GraphExtension" to 5,
        "GraphExtensionFactory" to 5,
        "Assisted" to 6,
        "AssistedInject" to 7,
        "ObjectClass" to 8,
        "GraphDependency" to 9,
        "MembersInjected" to 10,
        "CustomWrapper" to 11,
        "DefaultValue" to 12,
        "Absent" to 13,
      )

    private val kindColorMap =
      mapOf(
        "ConstructorInjected" to Colors.CONSTRUCTOR_INJECTED,
        "Provided" to Colors.PROVIDED,
        "Alias" to Colors.ALIAS,
        "BoundInstance" to Colors.BOUND_INSTANCE,
        "Multibinding" to Colors.MULTIBINDING,
        "GraphExtension" to Colors.GRAPH_EXTENSION,
        "GraphExtensionFactory" to Colors.GRAPH_EXTENSION,
        "Assisted" to Colors.ASSISTED,
        "AssistedInject" to Colors.ASSISTED,
        "ObjectClass" to Colors.OBJECT_CLASS,
        "GraphDependency" to Colors.GRAPH_DEPENDENCY,
        "MembersInjected" to Colors.MEMBERS_INJECTED,
        "CustomWrapper" to Colors.CUSTOM_WRAPPER,
        "DefaultValue" to Colors.DEFAULT_VALUE,
        "Absent" to Colors.OTHER,
      )

    private val graphNodeId = "graph:${metadata.graph}"
    private val graphPackage = extractPackage(metadata.graph)
    private val membersInjectedRoots = findMembersInjectedRoots()
    private val keyToProviderNodeId = buildProviderNodeIds()
    private val defaultValueNodes = findDefaultValues()
    private val defaultValueNodeMap = defaultValueNodes.associate {
      (it.consumerKey to it.targetType) to it.syntheticKey
    }
    private val rootMembers = buildRootMembers()
    private val typeNames = buildTypeNames()
    private val glowThresholds = calculateGlowThresholds()
    private val scopedKeys = metadata.bindings.filter { it.isScoped }.map { it.key }.toSet()
    private val bindingColorMap =
      metadata.bindings.associate {
        it.key to (kindColorMap[it.bindingKind] ?: Colors.OTHER)
      }

    fun build(): JsonObject = buildJsonObject {
      put("typeNames", json.encodeToJsonElement(typeNames))
      put("nodes", buildNodes())
      put("links", buildLinks())
    }

    private fun calculateGlowThresholds(): GlowThresholds {
      val metrics = analysis.bindingMetrics.values
      val graphSize = metadata.bindings.size.coerceAtLeast(1)

      // For centrality: use top 10% and top 25% as high/medium thresholds
      val centralityValues = metrics.map { it.betweennessCentrality }.filter { it > 0 }.sorted()
      val highCentralityThreshold =
        if (centralityValues.size >= 10) {
          centralityValues[centralityValues.size * 9 / 10] // 90th percentile
        } else {
          0.3 // fallback for small graphs
        }
      val mediumCentralityThreshold =
        if (centralityValues.size >= 4) {
          centralityValues[centralityValues.size * 3 / 4] // 75th percentile
        } else {
          0.1 // fallback for small graphs
        }

      // For dominator count: scale threshold with graph size (top ~10% of graph)
      val dominatorThreshold = (graphSize * 0.1).coerceAtLeast(3.0)

      // For fan-in: use 90th percentile or scale with graph size
      val fanInValues = metrics.map { it.fanIn }.filter { it > 0 }.sorted()
      val fanInThreshold =
        if (fanInValues.size >= 10) {
          fanInValues[fanInValues.size * 9 / 10].toDouble() // 90th percentile
        } else {
          (graphSize * 0.15).coerceAtLeast(3.0) // fallback
        }
      return GlowThresholds(
        highCentralityThreshold,
        mediumCentralityThreshold,
        dominatorThreshold,
        fanInThreshold,
      )
    }

    private fun findDefaultValues(): List<DefaultValueInfo> {
      val resolvedKeys =
        metadata.bindings.map { it.key }.toSet() +
          metadata.bindings.mapNotNull { it.assistedTarget?.key }
      val defaultValueNodes = mutableListOf<DefaultValueInfo>()
      for (binding in metadata.bindings) {
        for (dep in binding.dependencies) {
          if (dep.hasDefault) {
            // Strip " = ..." suffix from default value keys (e.g., "com.example.Analytics = ..." ->
            // "com.example.Analytics")
            val rawKey = dep.key.substringBefore(" = ")
            val targetKey = unwrapTypeKey(rawKey)
            if (targetKey in resolvedKeys) {
              continue
            }
            val syntheticKey = "default:$targetKey@${binding.key}"
            defaultValueNodes.add(
              DefaultValueInfo(
                syntheticKey = syntheticKey,
                targetType = targetKey,
                consumerKey = binding.key,
                targetPackage = extractPackage(targetKey),
              )
            )
          }
        }
      }
      return defaultValueNodes
    }

    private fun findMembersInjectedRoots(): Map<String, BindingMetadata> {
      val injectorKeys = metadata.roots?.injectors.orEmpty().map { unwrapTypeKey(it.key) }.toSet()
      // Injector roots carry member-injection dependencies.
      return metadata.bindings
        .filter { binding ->
          val isRoot = binding.declaration != null || binding.key in injectorKeys
          binding.bindingKind == "MembersInjected" && isRoot
        }
        .associateBy { it.key }
    }

    private fun buildProviderNodeIds(): Map<String, String> = buildMap {
      for (binding in metadata.bindings) {
        if (!isInjectorRoot(binding)) {
          put(binding.key, binding.key)
        }
        binding.assistedTarget?.let { target -> put(target.key, target.key) }
      }
    }

    private fun collectAccessors(): List<AccessorMetadata> {
      val accessors = metadata.roots?.accessors.orEmpty().toMutableList()
      val extensionAccessors =
        metadata.extensions?.accessors.orEmpty().map {
          AccessorMetadata(it.key, name = it.name, isProperty = it.isProperty)
        } +
          metadata.extensions?.factoryAccessors.orEmpty().map {
            AccessorMetadata(it.key, name = it.name, isProperty = it.isProperty)
          }
      for (accessor in extensionAccessors) {
        val alreadyRecorded = accessors.any {
          it.key == accessor.key && it.name == accessor.name && it.isProperty == accessor.isProperty
        }
        if (!alreadyRecorded) {
          accessors.add(accessor)
        }
      }
      return accessors
    }

    private fun resolveAccessorKey(accessor: AccessorMetadata): String? {
      val targetKey = unwrapTypeKey(accessor.key)
      if (targetKey in keyToProviderNodeId) {
        return targetKey
      }
      val selectedKeys =
        metadata.bindingExplanations
          .filter {
            it.request?.key == accessor.key && it.outcome == BindingExplanationOutcome.SELECTED
          }
          .flatMap { it.candidates }
          .filter { it.status == BindingCandidateStatus.SELECTED }
          .map { it.key }
          .distinct()
      return selectedKeys.singleOrNull()?.takeIf { it in keyToProviderNodeId }
    }

    private fun buildAccessorRoot(index: Int, accessor: AccessorMetadata): RootMemberInfo {
      val actualType = bindingType(accessor.key)
      val wrapperType =
        if (accessor.isDeferrable) {
          when {
            "Provider<" in actualType -> "Provider"
            "Lazy<" in actualType -> "Lazy"
            else -> null
          }
        } else {
          null
        }
      return RootMemberInfo(
        id = "root:${metadata.graph}:accessor:$index:${accessor.key}",
        key = accessor.key,
        kind = "accessor",
        name = accessor.name,
        isProperty = accessor.isProperty,
        resolvedKey = resolveAccessorKey(accessor),
        isDeferrable = accessor.isDeferrable,
        wrapperType = wrapperType,
        isInherited = accessor.isInherited,
        declaringGraph = accessor.declaringGraph,
        declaringType = accessor.declaringType,
        origin = accessor.origin,
      )
    }

    private fun buildRootMembers(): List<RootMemberInfo> = buildList {
      collectAccessors().forEachIndexed { index, accessor ->
        add(buildAccessorRoot(index, accessor))
      }
      metadata.roots?.injectors.orEmpty().forEachIndexed { index, injector ->
        val targetKey = unwrapTypeKey(injector.key)
        add(
          RootMemberInfo(
            id = "root:${metadata.graph}:injector:$index:${injector.key}",
            key = injector.key,
            kind = "injector",
            name = injector.name,
            binding = membersInjectedRoots[targetKey],
          )
        )
      }
    }

    private fun buildTypeNames(): Map<String, String> {
      val nodeTypeKeys = buildList {
        add(metadata.graph)
        addAll(rootMembers.map { it.key })
        addAll(rootMembers.mapNotNull { it.declaringType })
        for (binding in metadata.bindings) {
          if (!isInjectorRoot(binding)) {
            add(binding.key)
          }
          binding.assistedTarget?.let { add(it.key) }
        }
        addAll(defaultValueNodes.map { it.targetType })
      }
      return typeDisplayNames(nodeTypeKeys)
    }

    private fun isInjectorRoot(binding: BindingMetadata): Boolean =
      binding.bindingKind == "MembersInjected" && binding.key in membersInjectedRoots

    private fun isGraphExtension(binding: BindingMetadata): Boolean =
      binding.bindingKind == "GraphExtension" || binding.bindingKind == "GraphExtensionFactory"

    private fun buildNodes(): JsonArray = buildJsonArray {
      add(buildGraphNode())
      for (member in rootMembers) {
        add(buildRootNode(member))
      }
      for (binding in metadata.bindings) {
        if (!isInjectorRoot(binding)) {
          add(buildBindingNode(binding))
        }
      }
      for (defaultInfo in defaultValueNodes) {
        add(buildDefaultValueNode(defaultInfo))
      }
      for (binding in metadata.bindings) {
        val target = binding.assistedTarget ?: continue
        add(buildAssistedTargetNode(target))
      }
    }

    private fun buildGraphNode(): JsonObject = buildJsonObject {
      put("id", JsonPrimitive(graphNodeId))
      put("name", JsonPrimitive(extractDisplayName(metadata.graph, typeNames)))
      put("fullKey", JsonPrimitive(metadata.graph))
      put("pkg", JsonPrimitive(graphPackage))
      put("kind", JsonPrimitive("Graph"))
      put("isGraph", JsonPrimitive(true))
      put("isGraphInput", JsonPrimitive(false))
      put("synthetic", JsonPrimitive(false))
      put("category", JsonPrimitive(16))
      put("symbol", JsonPrimitive("diamond"))
      put("symbolSize", JsonPrimitive(28))
      put("rawDependencies", JsonArray(emptyList()))
      put("itemStyle", buildJsonObject { put("color", JsonPrimitive(Colors.GRAPH_NODE_BORDER)) })
    }

    private fun rootDisplayName(member: RootMemberInfo): String {
      val declaration = member.name
      val isAccessor = member.kind == "accessor"
      val displayType = extractDisplayName(member.key, typeNames)
      val actualType = bindingType(member.key)
      val hasInjectorWrapper =
        actualType.startsWith("dev.zacsweers.metro.MembersInjector<") ||
          actualType.startsWith("MembersInjector<")
      val injectorTarget =
        if (hasInjectorWrapper && actualType.endsWith('>')) {
          actualType.substringAfter('<').dropLast(1)
        } else {
          member.key
        }
      val accessorName =
        if (declaration != null && member.isProperty) {
          declaration
        } else if (declaration != null) {
          "$declaration()"
        } else if (!isAccessor) {
          "MembersInjector<${extractDisplayName(injectorTarget, typeNames)}>"
        } else {
          displayType
        }
      return if (member.isInherited && member.declaringType != null) {
        "${extractDisplayName(member.declaringType, typeNames)}.$accessorName"
      } else {
        accessorName
      }
    }

    private fun buildRootNode(member: RootMemberInfo): JsonObject = buildJsonObject {
      val isAccessor = member.kind == "accessor"
      val color =
        if (isAccessor) {
          Colors.EDGE_ACCESSOR
        } else {
          Colors.MEMBERS_INJECTED
        }
      put("id", JsonPrimitive(member.id))
      put("name", JsonPrimitive(rootDisplayName(member)))
      put("typeLabel", JsonPrimitive(extractDisplayName(member.key, typeNames)))
      put("isProperty", JsonPrimitive(member.isProperty))
      put("fullKey", JsonPrimitive(member.key))
      put("requestedKey", JsonPrimitive(member.key))
      put("pkg", JsonPrimitive(graphPackage))
      put(
        "kind",
        JsonPrimitive(
          if (isAccessor) {
            "Accessor"
          } else {
            "Injector"
          }
        ),
      )
      put("isRootMember", JsonPrimitive(true))
      put("rootOwner", JsonPrimitive(graphNodeId))
      put("rootKind", JsonPrimitive(member.kind))
      if (member.isInherited) {
        put("isInheritedRoot", JsonPrimitive(true))
        put("declaringGraph", JsonPrimitive(member.declaringGraph))
        put("declaringType", JsonPrimitive(member.declaringType))
      }
      put("isGraph", JsonPrimitive(false))
      put("isGraphInput", JsonPrimitive(false))
      put("synthetic", JsonPrimitive(false))
      put("scoped", JsonPrimitive(false))
      put("isDeferrable", JsonPrimitive(member.isDeferrable))
      put("declaration", JsonPrimitive(member.name.orEmpty()))
      put("origin", JsonPrimitive(member.origin ?: member.binding?.origin.orEmpty()))
      put(
        "category",
        JsonPrimitive(
          if (isAccessor) {
            14
          } else {
            15
          }
        ),
      )
      put("itemStyle", buildJsonObject { put("color", JsonPrimitive(color)) })
      if (isAccessor) {
        put(
          "rawDependencies",
          json.encodeToJsonElement(
            listOf(DependencyMetadata(member.key, false, member.wrapperType))
          ),
        )
        put("resolutionUnavailable", JsonPrimitive(member.resolvedKey == null))
      } else {
        put("injectorTarget", JsonPrimitive(member.key))
        put("rawDependencies", json.encodeToJsonElement(member.binding?.dependencies.orEmpty()))
        put("resolutionUnavailable", JsonPrimitive(member.binding == null))
      }
    }

    private fun buildBindingNode(binding: BindingMetadata): JsonObject = buildJsonObject {
      val isSynthetic =
        binding.isSynthetic ||
          binding.bindingKind == "Alias" ||
          binding.key.contains("MetroContribution")
      val isMainGraph = binding.key == metadata.graph
      val isGraphExtension = isGraphExtension(binding)
      val symbol =
        if (isGraphExtension) {
          "roundRect"
        } else {
          "circle"
        }
      val baseSize =
        when {
          isGraphExtension -> 22
          binding.isScoped -> 20
          else -> 12
        }
      val metrics = analysis.bindingMetrics[binding.key]
      // Links refer to the complete binding key.
      bindingDetails(binding)
      put("id", JsonPrimitive(binding.key))
      put("name", JsonPrimitive(extractDisplayName(binding.key, typeNames)))
      put("fullKey", JsonPrimitive(binding.key))
      put("pkg", JsonPrimitive(extractPackage(binding.key)))
      put("kind", JsonPrimitive(binding.bindingKind))
      put("scoped", JsonPrimitive(binding.isScoped))
      put("synthetic", JsonPrimitive(isSynthetic))
      put("isGraph", JsonPrimitive(false))
      put("isGraphInstance", JsonPrimitive(isMainGraph))
      put(
        "isGraphInput",
        JsonPrimitive(
          binding.isGraphInput ?: (binding.bindingKind == "BoundInstance" && !isMainGraph)
        ),
      )
      put("isIncludedGraph", JsonPrimitive(binding.key in metadata.includedGraphKeys))
      put("isExtension", JsonPrimitive(isGraphExtension))
      put("scope", binding.scope?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
      put("origin", binding.origin?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
      put("category", JsonPrimitive(categoryMap[binding.bindingKind] ?: 11))
      put("symbol", JsonPrimitive(symbol))
      put("symbolSize", JsonPrimitive(baseSize))

      // Analysis metrics (if available)
      if (metrics != null) {
        put("fanIn", JsonPrimitive(metrics.fanIn))
        put("fanOut", JsonPrimitive(metrics.fanOut))
        put("centrality", JsonPrimitive(metrics.betweennessCentrality))
        put("dominatorCount", JsonPrimitive(metrics.dominatorCount))
      }
      put("itemStyle", bindingNodeStyle(binding, isSynthetic, metrics))
    }

    private fun bindingNodeStyle(
      binding: BindingMetadata,
      isSynthetic: Boolean,
      metrics: BindingAnalysisMetrics?,
    ): JsonObject = buildJsonObject {
      // Main graph gets green fill+border, extensions get orange border
      when {
        binding.key == metadata.graph -> {
          put("color", JsonPrimitive(Colors.GRAPH_NODE_BORDER))
          put("borderColor", JsonPrimitive(Colors.GRAPH_NODE_BORDER))
          put("borderWidth", JsonPrimitive(3))
        }
        isGraphExtension(binding) -> {
          put("color", JsonPrimitive(Colors.EXTENSION_NODE_BORDER))
          put("borderColor", JsonPrimitive(Colors.EXTENSION_NODE_BORDER))
          put("borderWidth", JsonPrimitive(3))
        }
        binding.isScoped -> {
          put("borderColor", JsonPrimitive(Colors.SCOPED_BORDER))
          put("borderWidth", JsonPrimitive(3))
        }
      }
      if (isSynthetic) {
        put("opacity", JsonPrimitive(0.6))
      }

      if (metrics != null && binding.key != metadata.graph) {
        applyBindingGlow(metrics)
      }
    }

    private fun JsonObjectBuilder.applyBindingGlow(metrics: BindingAnalysisMetrics) {
      when {
        metrics.betweennessCentrality > glowThresholds.highCentrality -> {
          // High centrality (top 10%) - orange/red glow
          put("shadowBlur", JsonPrimitive(15))
          put("shadowColor", JsonPrimitive("#ff6b6b"))
        }
        metrics.betweennessCentrality > glowThresholds.mediumCentrality -> {
          // Medium centrality (top 25%) - yellow glow
          put("shadowBlur", JsonPrimitive(10))
          put("shadowColor", JsonPrimitive("#F6BC26"))
        }
        metrics.dominatorCount > glowThresholds.dominatorCount -> {
          // High dominator count (top ~10% of graph size) - red glow
          put("shadowBlur", JsonPrimitive(12))
          put("shadowColor", JsonPrimitive("#D82233"))
        }
        metrics.fanIn > glowThresholds.fanIn -> {
          // High fan-in (top 10%) - blue glow
          put("shadowBlur", JsonPrimitive(8))
          put("shadowColor", JsonPrimitive("#0078C6"))
        }
      }
    }

    private fun buildDefaultValueNode(defaultInfo: DefaultValueInfo): JsonObject = buildJsonObject {
      put("id", JsonPrimitive(defaultInfo.syntheticKey))
      put("name", JsonPrimitive(extractDisplayName(defaultInfo.targetType, typeNames)))
      put("fullKey", JsonPrimitive(defaultInfo.syntheticKey))
      put("pkg", JsonPrimitive(defaultInfo.targetPackage))
      put("kind", JsonPrimitive("DefaultValue"))
      put("scoped", JsonPrimitive(false))
      put("synthetic", JsonPrimitive(true))
      put("isGraph", JsonPrimitive(false))
      put("isExtension", JsonPrimitive(false))
      put("isDefaultValue", JsonPrimitive(true))
      put("rawDependencies", JsonArray(emptyList()))
      put("scope", JsonPrimitive(""))
      put("origin", JsonPrimitive(""))
      put("category", JsonPrimitive(categoryMap["DefaultValue"] ?: 12))
      put("symbol", JsonPrimitive("pin")) // Pin shape for default values
      put("symbolSize", JsonPrimitive(16))
      put("itemStyle", buildJsonObject { put("opacity", JsonPrimitive(0.8)) })
    }

    private fun buildAssistedTargetNode(target: AssistedTargetMetadata): JsonObject =
      buildJsonObject {
        put("declaration", JsonPrimitive(target.declaration.orEmpty()))
        put("rawDependencies", json.encodeToJsonElement(target.dependencies))
        put("multibinding", json.encodeToJsonElement(target.multibinding))
        put("optionalWrapper", json.encodeToJsonElement(target.optionalWrapper))
        put("id", JsonPrimitive(target.key))
        put("name", JsonPrimitive(extractDisplayName(target.key, typeNames)))
        put("fullKey", JsonPrimitive(target.key))
        put("pkg", JsonPrimitive(extractPackage(target.key)))
        put("kind", JsonPrimitive("AssistedInject"))
        put("scoped", JsonPrimitive(target.isScoped))
        put("synthetic", JsonPrimitive(false))
        put("isGraph", JsonPrimitive(false))
        put("isExtension", JsonPrimitive(false))
        put("isAssistedTarget", JsonPrimitive(true))
        put("scope", target.scope?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
        put("origin", target.origin?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
        put("category", JsonPrimitive(categoryMap["AssistedInject"] ?: 7))
        put("symbol", JsonPrimitive("circle"))
        put(
          "symbolSize",
          JsonPrimitive(
            if (target.isScoped) {
              20
            } else {
              14
            }
          ),
        )
        // Include assisted parameters for tooltip display
        put(
          "assistedParams",
          buildJsonArray {
            for (param in target.assistedParameters) {
              add(
                buildJsonObject {
                  put("name", JsonPrimitive(param.name))
                  put("type", JsonPrimitive(extractDisplayName(param.key, typeNames)))
                  put("key", JsonPrimitive(param.key))
                }
              )
            }
          },
        )
        put(
          "itemStyle",
          buildJsonObject {
            put("color", JsonPrimitive(Colors.ASSISTED))
            if (target.isScoped) {
              put("borderColor", JsonPrimitive(Colors.SCOPED_BORDER))
              put("borderWidth", JsonPrimitive(3))
            }
          },
        )
      }

    private fun buildLinks(): JsonArray = buildJsonArray {
      for (binding in metadata.bindings) {
        if (!isInjectorRoot(binding)) {
          addAll(buildBindingLinks(binding))
        }
      }
      for (member in rootMembers) {
        addAll(buildRootLinks(member))
      }
      for (binding in metadata.bindings) {
        val target = binding.assistedTarget ?: continue
        addAll(buildAssistedTargetLinks(binding, target))
      }
    }

    private fun buildBindingLinks(binding: BindingMetadata): JsonArray = buildJsonArray {
      val multibindingSourceKeys = binding.multibinding?.sources?.toSet() ?: emptySet()
      for (dependency in binding.dependencies) {
        val targetKey = unwrapTypeKey(dependency.key.substringBefore(" = "))
        val defaultKey = defaultValueNodeMap[binding.key to targetKey]
        if (defaultKey != null) {
          addAll(buildDefaultValueLinks(binding.key, defaultKey, targetKey))
        } else if (targetKey in keyToProviderNodeId) {
          val edgeType = dependencyEdgeType(binding, dependency, targetKey, multibindingSourceKeys)
          add(buildDependencyLink(binding, dependency, targetKey, edgeType))
        }
      }
    }

    private fun buildDefaultValueLinks(
      consumerKey: String,
      defaultKey: String,
      targetKey: String,
    ): JsonArray = buildJsonArray {
      // Edge from consumer to default value node
      add(
        buildJsonObject {
          put("source", JsonPrimitive(consumerKey))
          put("target", JsonPrimitive(defaultKey))
          put("edgeType", JsonPrimitive("default"))
          put(
            "lineStyle",
            buildJsonObject {
              put("color", JsonPrimitive(Colors.DEFAULT_VALUE))
              put("type", JsonPrimitive("dashed"))
              put("width", JsonPrimitive(2))
            },
          )
        }
      )

      // Edge from default value node to actual binding (if it exists)
      if (targetKey in keyToProviderNodeId) {
        add(
          buildJsonObject {
            put("source", JsonPrimitive(defaultKey))
            put("target", JsonPrimitive(targetKey))
            put("edgeType", JsonPrimitive("default-resolves"))
            put(
              "lineStyle",
              buildJsonObject {
                put("color", JsonPrimitive(Colors.DEFAULT_VALUE))
                put("type", JsonPrimitive("dotted"))
                put("opacity", JsonPrimitive(0.6))
              },
            )
          }
        )
      }
    }

    private fun dependencyEdgeType(
      binding: BindingMetadata,
      dependency: DependencyMetadata,
      targetKey: String,
      multibindingSourceKeys: Set<String>,
    ): String {
      val isInheritedScope = isGraphExtension(binding) && targetKey in scopedKeys
      val isAlias = binding.bindingKind == "Alias"
      val isAssistedFactory = binding.bindingKind == "Assisted"
      val isMultibinding = binding.multibinding != null
      val includedOwner = binding.graphDependency?.takeUnless { it.fromParent }?.ownerKey
      val isIncludedDependency =
        includedOwner == dependency.key && includedOwner in metadata.includedGraphKeys
      return when {
        isIncludedDependency -> "includes"
        isInheritedScope -> "inherited"
        isAlias -> "alias"
        // Assisted factory edges to its target's dependencies are "assisted" type
        isAssistedFactory -> "assisted"
        dependency.isDeferrable -> "deferrable"
        isMultibinding && dependency.key in multibindingSourceKeys -> "multibinding"
        else -> "normal"
      }
    }

    private fun buildDependencyLink(
      binding: BindingMetadata,
      dependency: DependencyMetadata,
      targetKey: String,
      edgeType: String,
    ): JsonObject = buildJsonObject {
      val edgeValue =
        when (edgeType) {
          "alias",
          "assisted" -> 0.3
          else -> 1.0
        }
      put("source", JsonPrimitive(binding.key))
      put("target", JsonPrimitive(targetKey))
      put("edgeType", JsonPrimitive(edgeType))
      put("hasDefault", JsonPrimitive(dependency.hasDefault))
      put("value", JsonPrimitive(edgeValue))
      // Include wrapper type for deferrable edges
      if (edgeType == "deferrable" && dependency.wrapperType != null) {
        put("wrapperType", JsonPrimitive(dependency.wrapperType))
      }

      val sourceColor = bindingColorMap[binding.key] ?: Colors.OTHER
      put("lineStyle", dependencyLineStyle(edgeType, sourceColor))
    }

    private fun dependencyLineStyle(edgeType: String, sourceColor: String): JsonObject =
      buildJsonObject {
        when (edgeType) {
          "inherited" -> {
            put("color", JsonPrimitive(Colors.EDGE_INHERITED))
            put("type", JsonPrimitive("dashed"))
            put("width", JsonPrimitive(2))
          }
          "accessor" -> {
            put("color", JsonPrimitive(Colors.EDGE_ACCESSOR))
            put("width", JsonPrimitive(2))
          }
          "alias" -> {
            put("color", JsonPrimitive(Colors.EDGE_ALIAS))
            put("type", JsonPrimitive("dotted"))
            put("width", JsonPrimitive(2))
            put("curveness", JsonPrimitive(0.35))
          }
          "deferrable" -> {
            put("color", JsonPrimitive(sourceColor))
            put("type", JsonPrimitive("dashed"))
          }
          "assisted" -> {
            put("color", JsonPrimitive(Colors.EDGE_ASSISTED))
            put("width", JsonPrimitive(2))
            put("curveness", JsonPrimitive(0.05))
          }
          "multibinding" -> {
            put("color", JsonPrimitive(Colors.EDGE_MULTIBINDING))
          }
          else -> {
            // Normal edges inherit color from source binding
            put("color", JsonPrimitive(sourceColor))
          }
        }
      }

    private fun buildRootLinks(member: RootMemberInfo): JsonArray = buildJsonArray {
      add(
        buildJsonObject {
          put("source", JsonPrimitive(graphNodeId))
          put("target", JsonPrimitive(member.id))
          put("edgeType", JsonPrimitive("root"))
          put("rootMembership", JsonPrimitive(true))
        }
      )
      if (member.kind == "accessor") {
        val targetKey = member.resolvedKey
        if (targetKey != null) {
          add(buildAccessorLink(member, targetKey))
        }
      } else {
        addAll(buildInjectorLinks(member))
      }
    }

    private fun buildAccessorLink(member: RootMemberInfo, targetKey: String): JsonObject =
      buildJsonObject {
        put("source", JsonPrimitive(member.id))
        put("target", JsonPrimitive(targetKey))
        put(
          "edgeType",
          JsonPrimitive(
            if (member.isDeferrable) {
              "deferrable"
            } else {
              "accessor"
            }
          ),
        )
        put("rootKind", JsonPrimitive("accessor"))
        put("requestedKey", JsonPrimitive(member.key))
        put("isDeferrable", JsonPrimitive(member.isDeferrable))
        if (member.wrapperType != null) {
          put("wrapperType", JsonPrimitive(member.wrapperType))
        }
        put(
          "lineStyle",
          buildJsonObject {
            put("color", JsonPrimitive(Colors.EDGE_ACCESSOR))
            put("width", JsonPrimitive(2))
            if (member.isDeferrable) {
              put("type", JsonPrimitive("dashed"))
            }
          },
        )
      }

    private fun buildInjectorLinks(member: RootMemberInfo): JsonArray = buildJsonArray {
      val binding = member.binding ?: return@buildJsonArray
      for (dependency in binding.dependencies) {
        val targetKey = unwrapTypeKey(dependency.key.substringBefore(" = "))
        val defaultKey = defaultValueNodeMap[binding.key to targetKey]
        val resolvedKey = defaultKey ?: keyToProviderNodeId[targetKey]
        if (resolvedKey == null) {
          continue
        }
        add(
          buildJsonObject {
            put("source", JsonPrimitive(member.id))
            put("target", JsonPrimitive(resolvedKey))
            put("edgeType", JsonPrimitive("injects"))
            put("rootKind", JsonPrimitive("injector"))
            put("injectorTarget", JsonPrimitive(member.key))
            put("requestedKey", JsonPrimitive(dependency.key))
            put("hasDefault", JsonPrimitive(dependency.hasDefault))
            if (dependency.wrapperType != null) {
              put("wrapperType", JsonPrimitive(dependency.wrapperType))
            }
            put(
              "lineStyle",
              buildJsonObject {
                put("color", JsonPrimitive(Colors.MEMBERS_INJECTED))
                put("type", JsonPrimitive("dashed"))
                put("width", JsonPrimitive(2))
              },
            )
          }
        )
      }
    }

    private fun buildAssistedTargetLinks(
      binding: BindingMetadata,
      target: AssistedTargetMetadata,
    ): JsonArray = buildJsonArray {
      // Edge from factory to target (dashed to indicate factory creates instances)
      add(
        buildJsonObject {
          put("source", JsonPrimitive(binding.key))
          put("target", JsonPrimitive(target.key))
          put("edgeType", JsonPrimitive("assisted"))
          put("value", JsonPrimitive(0.3)) // Short edge for direct relationship
          put(
            "lineStyle",
            buildJsonObject {
              put("color", JsonPrimitive(Colors.EDGE_ASSISTED))
              put("type", JsonPrimitive("dashed"))
              put("width", JsonPrimitive(2))
            },
          )
        }
      )

      // Edges from target to its dependencies
      for (dep in target.dependencies) {
        val depTargetKey = unwrapTypeKey(dep.key.substringBefore(" = "))
        if (depTargetKey in keyToProviderNodeId) {
          val edgeType =
            if (dep.isDeferrable) {
              "deferrable"
            } else {
              "normal"
            }
          val sourceColor = Colors.ASSISTED
          add(
            buildJsonObject {
              put("source", JsonPrimitive(target.key))
              put("target", JsonPrimitive(depTargetKey))
              put("edgeType", JsonPrimitive(edgeType))
              put("hasDefault", JsonPrimitive(dep.hasDefault))
              put("value", JsonPrimitive(1.0))
              if (edgeType == "deferrable" && dep.wrapperType != null) {
                put("wrapperType", JsonPrimitive(dep.wrapperType))
              }
              put(
                "lineStyle",
                buildJsonObject {
                  put("color", JsonPrimitive(sourceColor))
                  if (edgeType == "deferrable") {
                    put("type", JsonPrimitive("dashed"))
                  }
                },
              )
            }
          )
        }
      }
    }
  }

  private fun getBindingCategories(): JsonArray {
    val categories =
      listOf(
        "ConstructorInjected" to Colors.CONSTRUCTOR_INJECTED,
        "Provided" to Colors.PROVIDED,
        "Alias" to Colors.ALIAS,
        "BoundInstance" to Colors.BOUND_INSTANCE,
        "Multibinding" to Colors.MULTIBINDING,
        "GraphExtension" to Colors.GRAPH_EXTENSION,
        "Assisted Factory" to Colors.ASSISTED,
        "Assisted Inject" to Colors.ASSISTED,
        "ObjectClass" to Colors.OBJECT_CLASS,
        "GraphDependency" to Colors.GRAPH_DEPENDENCY,
        "MembersInjected" to Colors.MEMBERS_INJECTED,
        "CustomWrapper" to Colors.CUSTOM_WRAPPER,
        "DefaultValue" to Colors.DEFAULT_VALUE,
        "Other" to Colors.OTHER,
        "Accessor" to Colors.EDGE_ACCESSOR,
        "Injector" to Colors.MEMBERS_INJECTED,
        "Graph" to Colors.GRAPH_NODE_BORDER,
      )

    return buildJsonArray {
      for ((name, color) in categories) {
        add(
          buildJsonObject {
            put("name", JsonPrimitive(name))
            put("itemStyle", buildJsonObject { put("color", JsonPrimitive(color)) })
          }
        )
      }
    }
  }
}

/**
 * Centralized color constants for graph visualization.
 *
 * Colors based on the NYC MTA subway line colors:
 * - Red (#D82233) - 1/2/3 lines
 * - Orange (#EB6800) - B/D/F/M lines
 * - Yellow (#F6BC26) - N/Q/R/W lines
 * - Light Green (#799534) - G line
 * - Dark Green (#009952) - 4/5/6 lines
 * - Blue (#0078C6) - A/C/E lines
 * - Purple (#9A38A1) - 7 line
 * - Grey (#7C858C) - L/S shuttles
 * - Brown (#8E5C33) - J/Z lines
 * - Teal (#008EB7) - T line (Second Ave)
 */
internal object Colors {
  // NYC Subway line colors
  private const val SUBWAY_RED = "#D82233" // 1/2/3
  private const val SUBWAY_ORANGE = "#EB6800" // B/D/F/M
  private const val SUBWAY_YELLOW = "#F6BC26" // N/Q/R/W
  private const val SUBWAY_LIGHT_GREEN = "#799534" // G
  private const val SUBWAY_DARK_GREEN = "#009952" // 4/5/6
  private const val SUBWAY_BLUE = "#0078C6" // A/C/E
  private const val SUBWAY_PURPLE = "#9A38A1" // 7
  private const val SUBWAY_GREY = "#7C858C" // L/S
  private const val SUBWAY_BROWN = "#8E5C33" // J/Z
  private const val SUBWAY_TEAL = "#008EB7" // T

  // Edge type colors (special cases - most edges inherit from source node)
  const val EDGE_ALIAS = SUBWAY_GREY
  const val EDGE_ACCESSOR = SUBWAY_DARK_GREEN // graph entry points - green
  const val EDGE_DEFAULT = SUBWAY_GREY // default value edges

  // Binding kind colors (for node fill AND edge color inheritance)
  const val CONSTRUCTOR_INJECTED = SUBWAY_BLUE // main building blocks - blue
  const val PROVIDED = SUBWAY_YELLOW // providers
  const val ALIAS = SUBWAY_GREY // synthetic aliases
  const val BOUND_INSTANCE = SUBWAY_TEAL // bound instances
  const val MULTIBINDING = SUBWAY_PURPLE // collections
  const val GRAPH_EXTENSION = SUBWAY_ORANGE // extensions
  const val ASSISTED = SUBWAY_RED // assisted factories and inject targets
  const val OBJECT_CLASS = SUBWAY_TEAL // object classes
  const val GRAPH_DEPENDENCY = SUBWAY_BLUE // graph dependencies
  const val MEMBERS_INJECTED = SUBWAY_LIGHT_GREEN // members injection
  const val CUSTOM_WRAPPER = SUBWAY_TEAL // custom wrappers
  const val DEFAULT_VALUE = SUBWAY_YELLOW // default value provider
  const val OTHER = SUBWAY_GREY

  // UI accent colors
  const val SCOPED_BORDER = "#FFFFFF" // scoped bindings - white for emphasis
  const val GRAPH_NODE_BORDER = SUBWAY_DARK_GREEN // main dependency graph - matches accessors
  const val EXTENSION_NODE_BORDER = SUBWAY_ORANGE // graph extensions - match extension color
  const val LONGEST_PATH = SUBWAY_RED // path highlight
  const val PRIMARY = SUBWAY_BLUE // link color

  // Edge type colors (for special edge types)
  const val EDGE_DEFERRABLE = SUBWAY_TEAL // Provider/Lazy
  const val EDGE_ASSISTED = SUBWAY_RED // assisted factory → assisted-inject
  const val EDGE_INHERITED = SUBWAY_ORANGE // inherited from parent - match extension color
  const val EDGE_MULTIBINDING = SUBWAY_PURPLE // multibinding contributions

  /** Distinct colors for package grouping - NYC Subway palette */
  val packageColors =
    listOf(
      SUBWAY_RED, // 1/2/3
      SUBWAY_DARK_GREEN, // 4/5/6
      SUBWAY_BLUE, // A/C/E
      SUBWAY_ORANGE, // B/D/F/M
      SUBWAY_YELLOW, // N/Q/R/W
      SUBWAY_PURPLE, // 7
      SUBWAY_LIGHT_GREEN, // G
      SUBWAY_BROWN, // J/Z
      SUBWAY_TEAL, // T
      SUBWAY_GREY, // L/S
      "#d62728", // red
      "#1f77b4", // dark blue
      "#2ca02c", // dark green
      "#9467bd", // dark purple
    )
}

/**
 * Unwraps wrapper types from a type key to find the underlying type.
 *
 * For example:
 * - `Provider<com.example.Foo>` → `com.example.Foo`
 * - `Lazy<com.example.Bar>` → `com.example.Bar`
 * - `kotlin.collections.Set<com.example.Plugin>` → `kotlin.collections.Set<com.example.Plugin>`
 *   (collections are not unwrapped as they are the actual type)
 * - `com.example.Baz` → `com.example.Baz` (unchanged)
 */
internal fun unwrapTypeKey(key: String): String {
  // Pattern for Provider<T> and Lazy<T> - these need to be unwrapped to find the target node
  val wrapperPrefixes =
    listOf(
      "Provider<",
      "Lazy<",
      "dev.zacsweers.metro.Provider<",
      "kotlin.Lazy<",
      "javax.inject.Provider<",
      "jakarta.inject.Provider<",
    )
  for (prefix in wrapperPrefixes) {
    if (key.startsWith(prefix) && key.endsWith(">")) {
      return key.removePrefix(prefix).removeSuffix(">")
    }
  }
  return key
}

/**
 * Extracts just the class name(s) from a fully qualified type, removing the package prefix.
 *
 * For `com.example.Presenter.Factory` → `Presenter.Factory` For `com.example.Interceptor` →
 * `Interceptor`
 *
 * Uses the convention that package segments are lowercase and class names start with uppercase.
 */
internal fun extractClassName(fqn: String): String {
  val segments = fqn.split('.')
  val classSegments = mutableListOf<String>()
  var foundClass = false

  for (segment in segments) {
    // Class names start with uppercase
    if (segment.isNotEmpty() && segment[0].isUpperCase()) {
      foundClass = true
    }
    if (foundClass) {
      classSegments.add(segment)
    }
  }

  return if (classSegments.isNotEmpty()) classSegments.joinToString(".")
  else fqn.substringAfterLast('.')
}

/**
 * Extracts a display-friendly short name from a type key.
 *
 * Handles generic types like `kotlin.collections.Set<com.example.Plugin>` → `Set<Plugin>` Handles
 * nested classes like `kotlin.collections.Set<com.example.Presenter.Factory>` →
 * `Set<Presenter.Factory>` Handles annotated types like `@annotation.Foo(...) com.example.Bar` →
 * `Bar`
 */
internal fun extractDisplayName(key: String, typeNames: Map<String, String> = emptyMap()): String {
  val actualType = bindingType(key)
  return qualifiedTypePattern.replace(actualType) { match ->
    typeNames[match.value] ?: extractClassName(match.value)
  }
}

private val qualifiedTypePattern = Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""")

/** Keeps package names when distinct types would otherwise have the same display name. */
private fun typeDisplayNames(keys: List<String>): Map<String, String> {
  val types =
    keys
      .flatMap { key -> qualifiedTypePattern.findAll(bindingType(key)).map { it.value }.toList() }
      .distinct()
  val typesByName = types.groupBy(::extractClassName)
  return types.associateWith { type ->
    val shortName = extractClassName(type)
    if (typesByName.getValue(shortName).size > 1) {
      type
    } else {
      shortName
    }
  }
}

/**
 * Extracts the package from a type key, handling generic types, annotated types, and nested
 * classes.
 *
 * For `kotlin.collections.Set<com.example.Plugin>`, extracts from the type parameter: `com.example`
 * For `@annotation.Foo(...) com.example.Bar`, extracts from the actual type: `com.example` For
 * `com.example.OuterClass.InnerClass`, extracts just: `com.example`
 *
 * Uses the convention that package segments are lowercase and class names start with uppercase.
 */
internal fun extractPackage(key: String): String {
  val actualType = bindingType(key)

  // For generic collection types, extract package from the type parameter
  val genericStart = actualType.indexOf('<')
  val typeToAnalyze =
    if (genericStart != -1) {
      val basePart = actualType.take(genericStart)
      // If it's a standard collection, use the type parameter's package
      if (basePart.startsWith("kotlin.collections.") || basePart.startsWith("java.util.")) {
        actualType.substring(genericStart + 1, actualType.length - 1).split(',').first().trim()
      } else {
        actualType
      }
    } else {
      actualType
    }

  // Split by dots and find the package boundary
  // Package segments are typically lowercase, class names start with uppercase
  val segments = typeToAnalyze.split('.')
  val packageSegments = mutableListOf<String>()

  for (segment in segments) {
    // Stop at the first segment that looks like a class name (starts with uppercase)
    if (segment.isNotEmpty() && segment[0].isUpperCase()) {
      break
    }
    packageSegments.add(segment)
  }

  return packageSegments.joinToString(".")
}

/** Removes leading qualifiers while retaining the bound type and its type arguments. */
private fun bindingType(key: String): String {
  var start = 0
  while (key.getOrNull(start) == '@') {
    var cursor = start + 1
    while (cursor < key.length && (key[cursor].isLetterOrDigit() || key[cursor] in "._$")) {
      cursor++
    }
    if (key.getOrNull(cursor) == '(') {
      var depth = 0
      var quoted = false
      var escaped = false
      do {
        val character = key[cursor++]
        if (quoted) {
          if (escaped) {
            escaped = false
          } else if (character == '\\') {
            escaped = true
          } else if (character == '"') {
            quoted = false
          }
        } else if (character == '"') {
          quoted = true
        } else if (character == '(') {
          depth++
        } else if (character == ')') {
          depth--
        }
      } while (cursor < key.length && depth > 0)
      if (depth > 0) {
        return key
      }
    }
    if (key.getOrNull(cursor)?.isWhitespace() != true) {
      return key
    }
    while (key.getOrNull(cursor)?.isWhitespace() == true) {
      cursor++
    }
    start = cursor
  }
  return key.substring(start)
}
