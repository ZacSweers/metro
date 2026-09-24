// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

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
import kotlinx.serialization.json.JsonObject
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
}

@OptIn(ExperimentalMetroGradleApi::class)
internal class GraphHtmlRenderer(
  report: FullAnalysisReport = FullAnalysisReport("", emptyList()),
  graphs: List<GraphMetadata> = emptyList(),
) {
  private val renderer = GraphReportRenderer(report, graphs)

  fun buildData(metadata: GraphMetadata): JsonObject = renderer.buildData(metadata)

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
}
