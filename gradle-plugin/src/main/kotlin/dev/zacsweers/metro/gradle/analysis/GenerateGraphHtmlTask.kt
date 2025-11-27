// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import java.io.File
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates interactive HTML visualizations of Metro dependency graphs using ECharts.
 *
 * The generated HTML files are self-contained and can be opened directly in a browser. They use
 * [Apache ECharts](https://echarts.apache.org/) for beautiful, interactive graph visualization with
 * the following features:
 * - **Force-directed layout**: Automatic node positioning with physics simulation
 * - **Interactive**: Drag nodes, zoom, pan, hover tooltips
 * - **Search & filter**: Find bindings by name, filter by type
 * - **Focus mode**: Click a node to highlight its connections
 * - **Legend**: Color-coded by binding kind with toggle visibility
 * - **Responsive**: Adapts to window size
 * - **Beautiful defaults**: Gradient backgrounds, smooth animations, professional styling
 *
 * One HTML file is generated per dependency graph, plus an index page.
 */
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
  }

  init {
    group = "metro"
    description = "Generates interactive HTML visualizations of Metro dependency graphs"
  }

  @TaskAction
  public fun generate() {
    val input = inputFile.get().asFile
    val outputDir = outputDirectory.get().asFile

    logger.lifecycle("Generating Metro graph visualizations from file://${input.absolutePath}")

    val metadata = json.decodeFromString<AggregatedGraphMetadata>(input.readText())

    // Parse analysis report
    val analysisInput = analysisFile.get().asFile
    logger.lifecycle("Including analysis data from file://${analysisInput.absolutePath}")
    val analysisReport = json.decodeFromString<FullAnalysisReport>(analysisInput.readText())

    // Build per-graph analysis lookup
    val analysisLookup = buildAnalysisLookup(analysisReport)

    outputDir.mkdirs()

    for (graphMetadata in metadata.graphs) {
      val graphAnalysis = analysisLookup[graphMetadata.graph] ?: GraphAnalysisData(emptyMap())
      val htmlContent = generateHtml(graphMetadata, graphAnalysis)

      val fileName = "${graphMetadata.graph.replace('.', '-')}.html"
      val outputFile = File(outputDir, fileName)
      outputFile.toPath().createParentDirectories()
      outputFile.toPath().writeText(htmlContent)

      logger.lifecycle("Generated file://${outputFile.absolutePath}")
    }

    // Generate index page
    val indexContent = generateIndex(metadata)
    val indexFile = File(outputDir, "index.html")
    indexFile.toPath().writeText(indexContent)
    logger.lifecycle("Generated file://${indexFile.absolutePath}")
  }

  /** Builds a lookup map from graph name to per-binding analysis metrics. */
  private fun buildAnalysisLookup(report: FullAnalysisReport): Map<String, GraphAnalysisData> {
    val result = mutableMapOf<String, GraphAnalysisData>()

    for (i in report.statistics.indices) {
      val graphName = report.statistics[i].graphName
      val fanAnalysis = report.fanAnalysis.getOrNull(i)
      val centrality = report.centrality.getOrNull(i)
      val dominators = report.dominators.getOrNull(i)

      // Build per-binding metrics map
      val bindingMetrics = mutableMapOf<String, BindingAnalysisMetrics>()

      // Fan-in/Fan-out
      fanAnalysis?.bindings?.forEach { fan ->
        bindingMetrics.getOrPut(fan.key) { BindingAnalysisMetrics() }.apply {
          fanIn = fan.fanIn
          fanOut = fan.fanOut
        }
      }

      // Centrality
      centrality?.centralityScores?.forEach { score ->
        bindingMetrics.getOrPut(score.key) { BindingAnalysisMetrics() }.apply {
          betweennessCentrality = score.normalizedCentrality
        }
      }

      // Dominator count
      dominators?.dominators?.forEach { dom ->
        bindingMetrics.getOrPut(dom.key) { BindingAnalysisMetrics() }.apply {
          dominatorCount = dom.dominatedCount
        }
      }

      result[graphName] = GraphAnalysisData(bindingMetrics)
    }

    return result
  }

  /** Analysis data for a single graph. */
  private data class GraphAnalysisData(val bindingMetrics: Map<String, BindingAnalysisMetrics>)

  /** Analysis metrics for a single binding. */
  private data class BindingAnalysisMetrics(
    var fanIn: Int = 0,
    var fanOut: Int = 0,
    var betweennessCentrality: Double = 0.0,
    var dominatorCount: Int = 0,
  )

  private fun generateIndex(metadata: AggregatedGraphMetadata): String {
    // language=html
    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Metro Dependency Graphs - ${metadata.projectPath}</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
      min-height: 100vh;
      color: #e0e0e0;
    }
    .container { max-width: 900px; margin: 0 auto; padding: 60px 20px; }
    h1 {
      font-size: 3rem;
      margin-bottom: 10px;
      background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
    }
    .subtitle { color: #888; font-family: monospace; font-size: 0.9rem; margin-bottom: 8px; }
    .count { color: #667eea; font-size: 1.1rem; margin-bottom: 40px; }
    .graph-list { list-style: none; }
    .graph-list li {
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 12px;
      padding: 20px 25px;
      margin-bottom: 12px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: all 0.3s ease;
    }
    .graph-list li:hover {
      background: rgba(102,126,234,0.1);
      border-color: rgba(102,126,234,0.3);
      transform: translateX(8px);
      box-shadow: 0 4px 20px rgba(102,126,234,0.2);
    }
    .graph-list a {
      color: #667eea;
      text-decoration: none;
      font-weight: 600;
      font-size: 1.1rem;
    }
    .graph-list a:hover { color: #8b9ff5; }
    .meta { display: flex; gap: 20px; align-items: center; }
    .binding-count {
      color: #888;
      font-size: 0.9rem;
      background: rgba(255,255,255,0.05);
      padding: 4px 12px;
      border-radius: 20px;
    }
    .arrow { color: #667eea; font-size: 1.2rem; }
  </style>
</head>
<body>
  <div class="container">
    <h1>Metro Graphs</h1>
    <p class="subtitle">${metadata.projectPath}</p>
    <p class="count">${metadata.graphCount} dependency graph${if (metadata.graphCount != 1) "s" else ""}</p>
    <ul class="graph-list">
${metadata.graphs.joinToString("\n") { graph ->
  val fileName = "${graph.graph.replace('.', '-')}.html"
  """      <li>
        <a href="$fileName">${graph.graph}</a>
        <div class="meta">
          <span class="binding-count">${graph.bindings.size} bindings</span>
          <span class="arrow">→</span>
        </div>
      </li>"""
}}
    </ul>
  </div>
</body>
</html>
"""
      .trimIndent()
  }

  private fun generateHtml(graphMetadata: GraphMetadata, analysis: GraphAnalysisData): String {
    val graphData = buildEChartsData(graphMetadata, analysis)
    val categories = getBindingCategories()
    val longestPath = computeLongestPath(graphMetadata)
    val packages =
      graphMetadata.bindings.map { extractPackage(it.key) }.distinct().sorted()

    // language=html
    return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${graphMetadata.graph} - Metro Graph</title>
  <script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: #0d1117;
      color: #e6edf3;
      overflow: hidden;
    }
    #app { display: flex; height: 100vh; }
    #sidebar {
      width: 340px;
      background: #161b22;
      border-right: 1px solid #30363d;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .sidebar-header {
      padding: 20px;
      border-bottom: 1px solid #30363d;
    }
    .sidebar-header h1 {
      font-size: 1.3rem;
      color: #58a6ff;
      margin-bottom: 4px;
      word-break: break-all;
    }
    .sidebar-header .full-name {
      font-size: 0.7rem;
      color: #8b949e;
      font-family: monospace;
      word-break: break-all;
    }
    .sidebar-content {
      flex: 1;
      overflow-y: auto;
      padding: 16px 20px;
    }
    .search-box {
      position: relative;
      margin-bottom: 16px;
    }
    .search-box input {
      width: 100%;
      padding: 10px 14px;
      padding-left: 36px;
      border: 1px solid #30363d;
      border-radius: 8px;
      background: #0d1117;
      color: #e6edf3;
      font-size: 14px;
      transition: border-color 0.2s;
    }
    .search-box input:focus {
      outline: none;
      border-color: #58a6ff;
      box-shadow: 0 0 0 3px rgba(88,166,255,0.15);
    }
    .search-box::before {
      content: "⌕";
      position: absolute;
      left: 12px;
      top: 50%;
      transform: translateY(-50%);
      color: #8b949e;
      font-size: 16px;
    }
    .section { margin-bottom: 20px; }
    .section-title {
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      color: #8b949e;
      margin-bottom: 10px;
      font-weight: 600;
    }
    .stats-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
    }
    .stat-card {
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      padding: 12px;
    }
    .stat-value { font-size: 1.5rem; font-weight: 700; color: #58a6ff; }
    .stat-label { font-size: 0.75rem; color: #8b949e; margin-top: 2px; }
    .toggle-group {
      display: flex;
      gap: 4px;
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      padding: 4px;
    }
    .toggle-btn {
      flex: 1;
      padding: 6px 10px;
      border: none;
      border-radius: 6px;
      background: transparent;
      color: #8b949e;
      font-size: 0.75rem;
      cursor: pointer;
      transition: all 0.15s;
    }
    .toggle-btn.active {
      background: #21262d;
      color: #e6edf3;
    }
    .toggle-btn:hover:not(.active) {
      color: #e6edf3;
    }
    .action-btn {
      width: 100%;
      padding: 10px 14px;
      border: 1px solid #30363d;
      border-radius: 8px;
      background: #0d1117;
      color: #e6edf3;
      font-size: 0.85rem;
      cursor: pointer;
      transition: all 0.15s;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
    }
    .action-btn:hover {
      background: #21262d;
      border-color: #8b949e;
    }
    .action-btn.active {
      background: #238636;
      border-color: #238636;
    }
    .action-btn .icon { font-size: 1rem; }
    .collapsible-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      cursor: pointer;
      user-select: none;
    }
    .collapsible-header .collapse-icon {
      transition: transform 0.2s;
      font-size: 0.7rem;
      color: #8b949e;
    }
    .collapsible-header.collapsed .collapse-icon {
      transform: rotate(-90deg);
    }
    .collapsible-content {
      overflow: hidden;
      transition: max-height 0.2s ease-out;
    }
    .collapsible-content.collapsed {
      max-height: 0 !important;
    }
    .package-filter {
      max-height: 120px;
      overflow-y: auto;
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      padding: 8px;
    }
    .edge-legend {
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      padding: 8px;
      margin-top: 8px;
    }
    .edge-legend-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 3px 6px;
      font-size: 0.75rem;
      color: #8b949e;
    }
    .edge-legend-item .edge-line {
      width: 20px;
      height: 2px;
    }
    .edge-legend-item .edge-line.deferrable {
      background: #17becf;
      border-style: dashed;
    }
    .edge-legend-item .edge-line.assisted {
      background: #ff7f0e;
      height: 3px;
    }
    .edge-legend-item .edge-line.multibinding {
      background: #9467bd;
    }
    .edge-legend-item .edge-line.optional {
      background: #8b949e;
      border-style: dashed;
      opacity: 0.5;
    }
    .edge-legend-item .edge-line.default {
      background: #ffc107;
      border-style: dashed;
    }
    .edge-legend-item .edge-line.alias {
      background: #9e9e9e;
      border-style: dotted;
    }
    .edge-legend-item .edge-line.accessor {
      background: #4fc3f7;
      height: 3px;
    }
    .edge-legend-item .edge-line.inherited {
      background: #ff44cc;
      border-style: dashed;
      height: 3px;
    }
    .edge-legend-item .edge-line.normal {
      background: #30363d;
    }
    .filter-toggle {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px;
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      cursor: pointer;
      font-size: 0.85rem;
      color: #8b949e;
      transition: all 0.15s;
    }
    .filter-toggle:hover {
      background: #21262d;
      color: #e6edf3;
    }
    .filter-toggle input {
      accent-color: #58a6ff;
      width: 16px;
      height: 16px;
    }
    .package-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 6px;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.8rem;
      color: #8b949e;
      transition: all 0.15s;
    }
    .package-item:hover {
      background: #21262d;
      color: #e6edf3;
    }
    .package-item input {
      accent-color: #58a6ff;
    }
    .package-item .pkg-color {
      width: 10px;
      height: 10px;
      border-radius: 2px;
    }
    #details {
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      padding: 16px;
    }
    #details.empty { color: #8b949e; font-style: italic; text-align: center; padding: 30px; }
    .detail-header {
      font-family: monospace;
      font-size: 0.85rem;
      color: #58a6ff;
      word-break: break-all;
      margin-bottom: 12px;
      padding-bottom: 12px;
      border-bottom: 1px solid #30363d;
    }
    .detail-row {
      display: flex;
      justify-content: space-between;
      padding: 6px 0;
      font-size: 0.85rem;
    }
    .detail-label { color: #8b949e; }
    .detail-value { color: #e6edf3; font-family: monospace; }
    .detail-value.scoped { color: #ff44cc; }
    .deps-section { margin-top: 16px; }
    .deps-title {
      font-size: 0.75rem;
      text-transform: uppercase;
      color: #8b949e;
      margin-bottom: 8px;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .deps-title .count {
      background: #30363d;
      padding: 2px 8px;
      border-radius: 10px;
      font-size: 0.7rem;
    }
    .deps-list {
      max-height: 120px;
      overflow-y: auto;
    }
    .dep-item {
      font-family: monospace;
      font-size: 0.8rem;
      padding: 4px 8px;
      margin: 2px 0;
      border-radius: 4px;
      cursor: pointer;
      color: #8b949e;
      transition: all 0.15s;
    }
    .dep-item:hover { background: #30363d; color: #58a6ff; }
    .controls {
      padding: 12px 20px;
      border-top: 1px solid #30363d;
      display: flex;
      gap: 8px;
    }
    .controls button {
      flex: 1;
      padding: 8px 12px;
      border: 1px solid #30363d;
      border-radius: 6px;
      background: #21262d;
      color: #e6edf3;
      font-size: 0.8rem;
      cursor: pointer;
      transition: all 0.15s;
    }
    .controls button:hover { background: #30363d; border-color: #8b949e; }
    #chart { flex: 1; background: #0d1117; }
    .longest-path-info {
      background: #0d1117;
      border: 1px solid #30363d;
      border-radius: 8px;
      padding: 12px;
      margin-top: 8px;
      font-size: 0.8rem;
    }
    .longest-path-info .path-length {
      color: #58a6ff;
      font-weight: 600;
    }
    .longest-path-info .path-nodes {
      color: #8b949e;
      font-family: monospace;
      font-size: 0.75rem;
      margin-top: 8px;
      word-break: break-all;
    }
  </style>
</head>
<body>
  <div id="app">
    <div id="sidebar">
      <div class="sidebar-header">
        <h1>${graphMetadata.graph.substringAfterLast('.')}</h1>
        <div class="full-name">${graphMetadata.graph}</div>
      </div>
      <div class="sidebar-content">
        <div class="search-box">
          <input type="text" id="search" placeholder="Search bindings...">
        </div>

        <div class="section">
          <div class="section-title">Layout</div>
          <div class="toggle-group">
            <button class="toggle-btn active" data-layout="force">Force</button>
            <button class="toggle-btn" data-layout="circular">Circular</button>
          </div>
        </div>

        <div class="section">
          <div class="section-title">Filters</div>
          <label class="filter-toggle">
            <input type="checkbox" id="hide-synthetic" checked>
            <span>Focus synthetic bindings</span>
          </label>
          <label class="filter-toggle" style="margin-top:8px">
            <input type="checkbox" id="scoped-only">
            <span>Focus only scoped bindings</span>
          </label>
          <label class="filter-toggle" style="margin-top:8px">
            <input type="checkbox" id="show-defaults">
            <span>Show default value bindings</span>
          </label>
          <label class="filter-toggle" style="margin-top:8px">
            <input type="checkbox" id="show-glow" checked>
            <span>Show metrics glow</span>
          </label>
        </div>

        <div class="section">
          <div class="section-title">Analysis</div>
          <button class="action-btn" id="longest-path-btn">
            <span class="icon">📏</span> Show Longest Path
          </button>
          <div id="longest-path-info" class="longest-path-info" style="display:none;">
            <div>Longest path: <span class="path-length">${longestPath.size} nodes</span></div>
            <div class="path-nodes">${longestPath.joinToString(" → ") { it.substringAfterLast('.') }}</div>
          </div>
        </div>

        <div class="section">
          <div class="section-title collapsible-header collapsed" id="packages-header">
            <span>Packages (${packages.size})</span>
            <span class="collapse-icon">▼</span>
          </div>
          <div class="collapsible-content collapsed" id="packages-content" style="max-height:0">
            <div class="package-filter" id="package-filter">
${packages.mapIndexed { i, pkg ->
  val color = Colors.packageColors[i % Colors.packageColors.size]
  """              <label class="package-item">
                <input type="checkbox" checked data-package="$pkg">
                <span class="pkg-color" style="background:$color"></span>
                <span>${pkg.ifEmpty { "(root)" }}</span>
              </label>"""
}.joinToString("\n")}
            </div>
          </div>
        </div>

        <div class="section">
          <div class="section-title collapsible-header collapsed" id="edges-header">
            <span>Edge Types</span>
            <span class="collapse-icon">▼</span>
          </div>
          <div class="collapsible-content collapsed" id="edges-content" style="max-height:0">
            <div class="edge-legend">
              <div class="edge-legend-item"><span class="edge-line normal"></span> Normal dependency</div>
              <div class="edge-legend-item"><span class="edge-line accessor"></span> Accessor (graph entry point)</div>
              <div class="edge-legend-item"><span class="edge-line inherited"></span> Inherited binding (from parent)</div>
              <div class="edge-legend-item"><span class="edge-line deferrable"></span> Deferrable (Provider/Lazy)</div>
              <div class="edge-legend-item"><span class="edge-line assisted"></span> Assisted injection</div>
              <div class="edge-legend-item"><span class="edge-line multibinding"></span> Multibinding source</div>
              <div class="edge-legend-item"><span class="edge-line alias"></span> Alias (type binding)</div>
              <div class="edge-legend-item"><span class="edge-line default"></span> Default value (fallback)</div>
            </div>
          </div>
        </div>

        <div class="section">
          <div class="section-title">Overview</div>
          <div class="stats-grid">
            <div class="stat-card">
              <div class="stat-value">${graphMetadata.bindings.size}</div>
              <div class="stat-label">Bindings</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">${graphMetadata.bindings.count { it.isScoped }}</div>
              <div class="stat-label">Scoped</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">${graphMetadata.bindings.sumOf { it.dependencies.size }}</div>
              <div class="stat-label">Edges</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">${graphMetadata.bindings.groupBy { it.bindingKind }.size}</div>
              <div class="stat-label">Types</div>
            </div>
          </div>
        </div>

        <div class="section">
          <div class="section-title">Selected Binding</div>
          <div id="details" class="empty">Click a node to view details</div>
        </div>
      </div>
      <div class="controls">
        <button id="reset-btn">Reset</button>
        <button id="center-btn">Center</button>
      </div>
    </div>
    <div id="chart"></div>
  </div>
  <script>
    const graphData = ${json.encodeToString(JsonObject.serializer(), graphData)};
    const categories = ${json.encodeToString(JsonArray.serializer(), categories)};
    const longestPath = ${json.encodeToString(JsonArray.serializer(), buildJsonArray { longestPath.forEach { add(JsonPrimitive(it)) } })};

    const chart = echarts.init(document.getElementById('chart'), 'dark');

    let currentLayout = 'force';
    let showingLongestPath = false;

    function getBaseOption() {
      return {
        backgroundColor: '#0d1117',
        tooltip: {
          trigger: 'item',
          backgroundColor: 'rgba(22,27,34,0.95)',
          borderColor: '#30363d',
          borderWidth: 1,
          padding: [12, 16],
          textStyle: { color: '#e6edf3', fontSize: 12 },
          formatter: function(params) {
            // HTML escape to prevent angle brackets from being interpreted as tags
            function esc(s) { return s ? s.replace(/</g, '&lt;').replace(/>/g, '&gt;') : s; }
            if (params.dataType === 'node') {
              const d = params.data;
              let html = '<div style="font-weight:600;color:#58a6ff;margin-bottom:8px">' + esc(d.name);
              if (d.isGraph) html += ' <span style="color:#ffd700;font-size:10px">◆ GRAPH</span>';
              else if (d.isExtension) html += ' <span style="color:#00bfff;font-size:10px">▢ EXTENSION</span>';
              else if (d.isDefaultValue) html += ' <span style="color:#ffc107;font-size:10px">📌 DEFAULT</span>';
              else if (d.synthetic) html += ' <span style="color:#8b949e;font-size:10px">(synthetic)</span>';
              html += '</div>';
              html += '<div style="color:#8b949e;font-size:11px">' + esc(d.fullKey) + '</div>';
              html += '<div style="margin-top:8px;padding-top:8px;border-top:1px solid #30363d">';
              html += '<div>Type: <span style="color:#e6edf3">' + d.kind + '</span></div>';
              html += '<div>Package: <span style="color:#e6edf3">' + (d.pkg || '(root)') + '</span></div>';
              if (d.scoped) html += '<div>Scoped: <span style="color:#ff44cc">Yes</span></div>';
              if (d.scope) html += '<div>Scope: <span style="color:#e6edf3">' + d.scope + '</span></div>';
              // Analysis metrics (if available) with heatmap coloring
              if (d.fanIn !== undefined || d.fanOut !== undefined) {
                html += '</div><div style="margin-top:8px;padding-top:8px;border-top:1px solid #30363d">';
                html += '<div style="font-size:10px;color:#8b949e;margin-bottom:4px">ANALYSIS</div>';
                // Fan-in with heatmap: green (low) -> yellow -> red (high)
                if (d.fanIn !== undefined) {
                  const fanInColor = d.fanIn > 10 ? '#f85149' : d.fanIn > 5 ? '#ffc107' : '#58a6ff';
                  html += '<div>Fan-in: <span style="color:' + fanInColor + '">' + d.fanIn + '</span></div>';
                }
                // Fan-out with heatmap
                if (d.fanOut !== undefined) {
                  const fanOutColor = d.fanOut > 8 ? '#f85149' : d.fanOut > 4 ? '#ffc107' : '#58a6ff';
                  html += '<div>Fan-out: <span style="color:' + fanOutColor + '">' + d.fanOut + '</span></div>';
                }
                // Centrality with heatmap
                if (d.centrality !== undefined && d.centrality > 0) {
                  const centralityColor = d.centrality > 0.3 ? '#ff6b6b' : d.centrality > 0.1 ? '#ffc107' : '#74c476';
                  html += '<div>Centrality: <span style="color:' + centralityColor + '">' + (d.centrality * 100).toFixed(1) + '%</span></div>';
                }
                // Dominator count with heatmap
                if (d.dominatorCount !== undefined && d.dominatorCount > 0) {
                  const domColor = d.dominatorCount > 10 ? '#f85149' : d.dominatorCount > 5 ? '#ffc107' : '#58a6ff';
                  html += '<div>Dominates: <span style="color:' + domColor + '">' + d.dominatorCount + ' bindings</span></div>';
                }
              }
              html += '</div>';
              return html;
            }
            if (params.dataType === 'edge') {
              const d = params.data;
              const edgeLabels = {
                'accessor': 'Accessor (graph entry point)',
                'inherited': 'Inherited binding (from parent graph)',
                'deferrable': 'Deferrable (Provider/Lazy)',
                'assisted': 'Assisted injection',
                'multibinding': 'Multibinding source',
                'alias': 'Alias (type binding)',
                'default': 'Default value (fallback available)',
                'default-resolves': 'Default resolves to binding',
                'normal': 'Normal dependency'
              };
              return edgeLabels[d.edgeType] || 'Dependency';
            }
            return '';
          }
        },
        legend: {
          type: 'scroll',
          orient: 'horizontal',
          bottom: 20,
          data: categories.map(c => c.name),
          textStyle: { color: '#8b949e', fontSize: 11 },
          pageTextStyle: { color: '#8b949e' },
          inactiveColor: '#30363d'
        },
        animationDuration: 800,
        animationEasingUpdate: 'quinticInOut'
      };
    }

    function getSeriesOption(layout) {
      const base = {
        type: 'graph',
        data: graphData.nodes,
        links: graphData.links,
        categories: categories,
        roam: true,
        draggable: true,
        label: {
          show: true,
          position: 'right',
          formatter: '{b}',
          fontSize: 10,
          color: '#8b949e'
        },
        labelLayout: { hideOverlap: true },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3 },
          label: { show: true, color: '#e6edf3' }
        },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 8],
        lineStyle: {
          color: '#30363d',
          width: 1.5,
          curveness: 0.2,
          opacity: 0.7
        },
        scaleLimit: { min: 0.2, max: 5 }
      };

      if (layout === 'force') {
        return {
          ...base,
          layout: 'force',
          force: {
            repulsion: 400,
            gravity: 0.1,
            edgeLength: [50, 200],
            layoutAnimation: true
          }
        };
      } else {
        return {
          ...base,
          layout: 'circular',
          circular: {
            rotateLabel: true
          }
        };
      }
    }

    function updateChart() {
      const option = getBaseOption();
      option.series = [getSeriesOption(currentLayout)];
      chart.setOption(option, true);
    }

    // Initial render - will be replaced by applyFilters() call at end of script

    // Build reverse dependency map
    const dependents = {};
    graphData.nodes.forEach(n => dependents[n.fullKey] = []);
    graphData.links.forEach(l => {
      if (dependents[l.target]) dependents[l.target].push(l.source);
    });

    // Node click handler
    chart.on('click', function(params) {
      if (params.dataType === 'node') {
        showDetails(params.data);
      }
    });

    function showDetails(node) {
      const deps = graphData.links.filter(l => l.source === node.fullKey).map(l => l.target);
      const depts = dependents[node.fullKey] || [];

      let html = '<div class="detail-header">' + node.fullKey + '</div>';
      html += '<div class="detail-row"><span class="detail-label">Kind</span><span class="detail-value">' + node.kind + '</span></div>';
      html += '<div class="detail-row"><span class="detail-label">Package</span><span class="detail-value">' + (node.pkg || '(root)') + '</span></div>';
      html += '<div class="detail-row"><span class="detail-label">Scoped</span><span class="detail-value' + (node.scoped ? ' scoped' : '') + '">' + (node.scoped ? 'Yes' : 'No') + '</span></div>';
      if (node.scope) {
        html += '<div class="detail-row"><span class="detail-label">Scope</span><span class="detail-value">' + node.scope + '</span></div>';
      }
      if (node.origin) {
        html += '<div class="detail-row"><span class="detail-label">Origin</span><span class="detail-value">' + node.origin + '</span></div>';
      }

      // Analysis metrics section with heatmap colors
      if (node.fanIn !== undefined || node.fanOut !== undefined) {
        html += '<div class="deps-section"><div class="deps-title">Analysis</div>';
        const fanInColor = node.fanIn > 10 ? '#f85149' : node.fanIn > 5 ? '#ffc107' : '#58a6ff';
        const fanOutColor = node.fanOut > 8 ? '#f85149' : node.fanOut > 4 ? '#ffc107' : '#58a6ff';
        html += '<div class="detail-row"><span class="detail-label">Fan-in</span><span class="detail-value" style="color:' + fanInColor + '">' + (node.fanIn || 0) + '</span></div>';
        html += '<div class="detail-row"><span class="detail-label">Fan-out</span><span class="detail-value" style="color:' + fanOutColor + '">' + (node.fanOut || 0) + '</span></div>';
        if (node.centrality !== undefined && node.centrality > 0) {
          const centralityColor = node.centrality > 0.3 ? '#ff6b6b' : node.centrality > 0.1 ? '#ffc107' : '#74c476';
          html += '<div class="detail-row"><span class="detail-label">Centrality</span><span class="detail-value" style="color:' + centralityColor + '">' + (node.centrality * 100).toFixed(1) + '%</span></div>';
        }
        if (node.dominatorCount !== undefined && node.dominatorCount > 0) {
          const domColor = node.dominatorCount > 10 ? '#f85149' : node.dominatorCount > 5 ? '#ffc107' : '#58a6ff';
          html += '<div class="detail-row"><span class="detail-label">Dominates</span><span class="detail-value" style="color:' + domColor + '">' + node.dominatorCount + ' bindings</span></div>';
        }
        html += '</div>';
      }

      if (deps.length > 0) {
        html += '<div class="deps-section"><div class="deps-title">Dependencies <span class="count">' + deps.length + '</span></div>';
        html += '<div class="deps-list">' + deps.map(d => '<div class="dep-item" onclick="focusNode(\'' + d.replace(/'/g, "\\'") + '\')">' + d.split('.').pop() + '</div>').join('') + '</div></div>';
      }
      if (depts.length > 0) {
        html += '<div class="deps-section"><div class="deps-title">Dependents <span class="count">' + depts.length + '</span></div>';
        html += '<div class="deps-list">' + depts.map(d => '<div class="dep-item" onclick="focusNode(\'' + d.replace(/'/g, "\\'") + '\')">' + d.split('.').pop() + '</div>').join('') + '</div></div>';
      }

      document.getElementById('details').innerHTML = html;
      document.getElementById('details').classList.remove('empty');
    }

    window.focusNode = function(key) {
      const node = graphData.nodes.find(n => n.fullKey === key);
      if (node) {
        chart.dispatchAction({ type: 'focusNodeAdjacency', dataIndex: graphData.nodes.indexOf(node) });
        showDetails(node);
      }
    };

    // Layout toggle
    document.querySelectorAll('.toggle-btn[data-layout]').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.toggle-btn[data-layout]').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        currentLayout = btn.dataset.layout;
        updateChart();
      });
    });

    // Longest path highlight
    document.getElementById('longest-path-btn').addEventListener('click', () => {
      showingLongestPath = !showingLongestPath;
      const btn = document.getElementById('longest-path-btn');
      const info = document.getElementById('longest-path-info');

      if (showingLongestPath) {
        btn.classList.add('active');
        btn.innerHTML = '<span class="icon">✓</span> Showing Longest Path';
        info.style.display = 'block';

        // Highlight longest path nodes and edges
        const pathSet = new Set(longestPath);
        const pathEdges = new Set();
        for (let i = 0; i < longestPath.length - 1; i++) {
          pathEdges.add(longestPath[i] + '→' + longestPath[i + 1]);
        }

        const newNodes = graphData.nodes.map(n => ({
          ...n,
          itemStyle: pathSet.has(n.fullKey)
            ? { borderColor: '#f85149', borderWidth: 4 }
            : { opacity: 0.2 }
        }));

        const newLinks = graphData.links.map(l => ({
          ...l,
          lineStyle: pathEdges.has(l.source + '→' + l.target)
            ? { color: '#f85149', width: 3, opacity: 1 }
            : { opacity: 0.1 }
        }));

        chart.setOption({ series: [{ data: newNodes, links: newLinks }] });
      } else {
        btn.classList.remove('active');
        btn.innerHTML = '<span class="icon">📏</span> Show Longest Path';
        info.style.display = 'none';
        updateChart();
      }
    });

    // Store original data for filtering (deep copy to avoid mutation)
    const originalNodes = JSON.parse(JSON.stringify(graphData.nodes));
    const originalLinks = JSON.parse(JSON.stringify(graphData.links));

    // Apply all filters (package, synthetic, scoped, defaults, search, glow)
    // Default value nodes are actually removed from the graph, others just get faded
    function applyFilters() {
      const showSynthetic = document.getElementById('hide-synthetic').checked;
      const scopedOnly = document.getElementById('scoped-only').checked;
      const showDefaults = document.getElementById('show-defaults').checked;
      const showGlow = document.getElementById('show-glow').checked;
      const enabledPackages = new Set();
      document.querySelectorAll('#package-filter input:checked').forEach(c => {
        enabledPackages.add(c.dataset.package);
      });
      const query = document.getElementById('search').value.toLowerCase();

      // Track which nodes pass the "removal" filters (default value toggle)
      const includedNodeKeys = new Set();
      // Track which nodes pass all filters (for opacity)
      const visibleNodeKeys = new Set();

      // First pass: determine which nodes to include (not filtered out entirely)
      originalNodes.forEach(n => {
        // Default value nodes are completely removed when filter is off
        const passesDefaults = showDefaults || !n.isDefaultValue;
        if (passesDefaults) {
          includedNodeKeys.add(n.fullKey);
        }
      });

      // Filter nodes - remove default value nodes if filter is off, fade others
      const newNodes = originalNodes.filter(n => includedNodeKeys.has(n.fullKey)).map(n => {
        // Check visibility filters (fade but don't remove)
        const passesPackage = enabledPackages.has(n.pkg);
        const passesSynthetic = showSynthetic || !n.synthetic;
        const passesScoped = !scopedOnly || n.scoped;
        const passesSearch = !query || n.fullKey.toLowerCase().includes(query) || n.name.toLowerCase().includes(query);

        const visible = passesPackage && passesSynthetic && passesScoped && passesSearch;
        if (visible) {
          visibleNodeKeys.add(n.fullKey);
        }

        // Apply visibility styling and glow toggle
        const baseStyle = n.itemStyle ? {...n.itemStyle} : {};
        if (!visible) {
          baseStyle.opacity = 0.1;
        }
        // Remove glow effects if toggle is off
        if (!showGlow) {
          delete baseStyle.shadowBlur;
          delete baseStyle.shadowColor;
        }
        return {...n, itemStyle: Object.keys(baseStyle).length > 0 ? baseStyle : undefined};
      });

      // Filter links - remove links to/from removed nodes, fade links to faded nodes
      const newLinks = originalLinks
        .filter(l => includedNodeKeys.has(l.source) && includedNodeKeys.has(l.target))
        .map(l => {
          const isVisible = visibleNodeKeys.has(l.source) && visibleNodeKeys.has(l.target);
          const style = l.lineStyle ? {...l.lineStyle} : {};
          style.opacity = isVisible ? (style.opacity || 0.7) : 0.05;
          return {...l, lineStyle: style};
        });

      // Rebuild full option to properly handle node addition/removal
      const option = getBaseOption();
      const seriesOpt = getSeriesOption(currentLayout);
      seriesOpt.data = newNodes;
      seriesOpt.links = newLinks;
      option.series = [seriesOpt];
      chart.setOption(option, true);
    }

    // Package filter
    document.querySelectorAll('#package-filter input').forEach(cb => {
      cb.addEventListener('change', applyFilters);
    });

    // Synthetic filter
    document.getElementById('hide-synthetic').addEventListener('change', applyFilters);

    // Scoped-only filter
    document.getElementById('scoped-only').addEventListener('change', applyFilters);

    // Default value filter
    document.getElementById('show-defaults').addEventListener('change', applyFilters);

    // Glow effects filter
    document.getElementById('show-glow').addEventListener('change', applyFilters);

    // Search
    document.getElementById('search').addEventListener('input', applyFilters);

    // Controls
    document.getElementById('reset-btn').addEventListener('click', () => {
      showingLongestPath = false;
      document.getElementById('longest-path-btn').classList.remove('active');
      document.getElementById('longest-path-btn').innerHTML = '<span class="icon">📏</span> Show Longest Path';
      document.getElementById('longest-path-info').style.display = 'none';
      document.querySelectorAll('#package-filter input').forEach(cb => cb.checked = true);
      document.getElementById('hide-synthetic').checked = true;
      document.getElementById('scoped-only').checked = false;
      document.getElementById('show-defaults').checked = true;
      document.getElementById('show-glow').checked = true;
      document.getElementById('search').value = '';
      updateChart();
    });

    document.getElementById('center-btn').addEventListener('click', () => {
      chart.resize();
    });

    // Resize handler
    window.addEventListener('resize', () => chart.resize());

    // Collapsible sections
    document.querySelectorAll('.collapsible-header').forEach(header => {
      header.addEventListener('click', () => {
        const contentId = header.id.replace('-header', '-content');
        const content = document.getElementById(contentId);
        if (content) {
          header.classList.toggle('collapsed');
          content.classList.toggle('collapsed');
          if (!content.classList.contains('collapsed')) {
            content.style.maxHeight = content.scrollHeight + 'px';
          } else {
            content.style.maxHeight = '0';
          }
        }
      });
    });

    // Initialize packages content max-height
    const packagesContent = document.getElementById('packages-content');
    if (packagesContent) {
      packagesContent.style.maxHeight = packagesContent.scrollHeight + 'px';
    }

    // Apply filters on initial load to respect default filter states
    applyFilters();
  </script>
</body>
</html>
"""
      .trimIndent()
  }

  private fun buildEChartsData(metadata: GraphMetadata, analysis: GraphAnalysisData): JsonObject {
    val categoryMap =
      mapOf(
        "ConstructorInjected" to 0,
        "Provided" to 1,
        "Alias" to 2,
        "BoundInstance" to 3,
        "Multibinding" to 4,
        "GraphExtension" to 5,
        "GraphExtensionFactory" to 5,
        "Assisted" to 6,
        "ObjectClass" to 7,
        "GraphDependency" to 8,
        "MembersInjected" to 9,
        "CustomWrapper" to 10,
        "DefaultValue" to 11,
        "Absent" to 12,
      )

    // Build set of multibinding source keys for edge coloring
    val multibindingSources =
      metadata.bindings
        .filter { it.multibinding != null }
        .flatMap { it.multibinding!!.sources }
        .toSet()

    // Collect dependencies with default values for synthetic node generation
    // Key: "default:{targetType}@{consumerKey}" to ensure uniqueness per usage site
    data class DefaultValueInfo(
      val syntheticKey: String,
      val targetType: String,
      val consumerKey: String,
      val targetDisplayName: String,
      val targetPackage: String,
    )

    val defaultValueNodes = mutableListOf<DefaultValueInfo>()
    for (binding in metadata.bindings) {
      for (dep in binding.dependencies) {
        if (dep.hasDefault) {
          // Strip " = ..." suffix from default value keys (e.g., "com.example.Analytics = ..." -> "com.example.Analytics")
          val rawKey = dep.key.substringBefore(" = ")
          val targetKey = unwrapTypeKey(rawKey)
          val syntheticKey = "default:$targetKey@${binding.key}"
          defaultValueNodes.add(
            DefaultValueInfo(
              syntheticKey = syntheticKey,
              targetType = targetKey,
              consumerKey = binding.key,
              targetDisplayName = extractDisplayName(targetKey),
              targetPackage = extractPackage(targetKey),
            )
          )
        }
      }
    }

    val nodes = buildJsonArray {
      for (binding in metadata.bindings) {
        // Determine if synthetic (infer if not explicitly set)
        val isSynthetic =
          binding.isSynthetic ||
            binding.bindingKind == "Alias" ||
            binding.key.contains("MetroContribution")

        // Determine if this is the main graph or a graph extension
        val isMainGraph = binding.key == metadata.graph
        val isGraphExtension =
          binding.bindingKind == "GraphExtension" || binding.bindingKind == "GraphExtensionFactory"

        // Use helper functions for display name and package extraction
        val displayName = extractDisplayName(binding.key)
        val pkg = extractPackage(binding.key)

        // Determine symbol (shape) and size based on node type
        val symbol =
          when {
            isMainGraph -> "diamond"
            isGraphExtension -> "roundRect"
            else -> "circle"
          }
        val baseSize =
          when {
            isMainGraph -> 28
            isGraphExtension -> 22
            binding.isScoped -> 20
            else -> 12
          }

        // Get analysis metrics for this binding
        val metrics = analysis.bindingMetrics[binding.key]

        add(
          buildJsonObject {
            // ECharts uses 'id' for link source/target matching
            put("id", JsonPrimitive(binding.key))
            put("name", JsonPrimitive(displayName))
            put("fullKey", JsonPrimitive(binding.key))
            put("pkg", JsonPrimitive(pkg))
            put("kind", JsonPrimitive(binding.bindingKind))
            put("scoped", JsonPrimitive(binding.isScoped))
            put("synthetic", JsonPrimitive(isSynthetic))
            put("isGraph", JsonPrimitive(isMainGraph))
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

            put(
              "itemStyle",
              buildJsonObject {
                // Only scoped bindings get a special border (graphs/extensions use shape only)
                if (binding.isScoped && !isMainGraph && !isGraphExtension) {
                  put("borderColor", JsonPrimitive(Colors.SCOPED_BORDER))
                  put("borderWidth", JsonPrimitive(3))
                }
                if (isSynthetic) {
                  put("opacity", JsonPrimitive(0.6))
                }
                // Add glow effect based on analysis metrics (centrality takes priority)
                // Skip glow for the main graph binding as it's expected to have high metrics
                if (metrics != null && !isMainGraph) {
                  when {
                    metrics.betweennessCentrality > 0.3 -> {
                      // High centrality - orange/red glow
                      put("shadowBlur", JsonPrimitive(15))
                      put("shadowColor", JsonPrimitive("#ff6b6b"))
                    }
                    metrics.betweennessCentrality > 0.1 -> {
                      // Medium centrality - yellow glow
                      put("shadowBlur", JsonPrimitive(10))
                      put("shadowColor", JsonPrimitive("#ffc107"))
                    }
                    metrics.dominatorCount > 10 -> {
                      // High dominator count - red glow
                      put("shadowBlur", JsonPrimitive(12))
                      put("shadowColor", JsonPrimitive("#f85149"))
                    }
                    metrics.fanIn > 10 -> {
                      // High fan-in - blue glow
                      put("shadowBlur", JsonPrimitive(8))
                      put("shadowColor", JsonPrimitive("#58a6ff"))
                    }
                  }
                }
              },
            )
          }
        )
      }

      // Add synthetic default value nodes
      for (defaultInfo in defaultValueNodes) {
        add(
          buildJsonObject {
            put("id", JsonPrimitive(defaultInfo.syntheticKey))
            put("name", JsonPrimitive(defaultInfo.targetDisplayName))
            put("fullKey", JsonPrimitive(defaultInfo.syntheticKey))
            put("pkg", JsonPrimitive(defaultInfo.targetPackage))
            put("kind", JsonPrimitive("DefaultValue"))
            put("scoped", JsonPrimitive(false))
            put("synthetic", JsonPrimitive(true))
            put("isGraph", JsonPrimitive(false))
            put("isExtension", JsonPrimitive(false))
            put("isDefaultValue", JsonPrimitive(true))
            put("scope", JsonPrimitive(""))
            put("origin", JsonPrimitive(""))
            put("category", JsonPrimitive(categoryMap["DefaultValue"] ?: 12))
            put("symbol", JsonPrimitive("pin")) // Pin shape for default values
            put("symbolSize", JsonPrimitive(16))
            put(
              "itemStyle",
              buildJsonObject {
                put("opacity", JsonPrimitive(0.8))
              },
            )
          }
        )
      }
    }

    // Build set of valid node keys for validation (including synthetic default value nodes)
    val nodeKeys = metadata.bindings.map { it.key }.toSet() +
      defaultValueNodes.map { it.syntheticKey }.toSet()

    // Build map from (consumerKey, targetType) -> syntheticKey for default value edge routing
    val defaultValueNodeMap = defaultValueNodes.associate { (it.consumerKey to it.targetType) to it.syntheticKey }

    // Build set of scoped binding keys for inherited scope detection
    val scopedKeys = metadata.bindings.filter { it.isScoped }.map { it.key }.toSet()

    val links = buildJsonArray {
      for (binding in metadata.bindings) {
        // Check if this is a multibinding (edges to sources)
        val isMultibinding = binding.multibinding != null
        val multibindingSourceKeys = binding.multibinding?.sources?.toSet() ?: emptySet()

        // Check if this is an assisted factory
        val isAssistedFactory = binding.bindingKind == "Assisted"

        // Check if this is an alias binding
        val isAlias = binding.bindingKind == "Alias"

        // Check if this is a graph extension
        val isGraphExtension =
          binding.bindingKind == "GraphExtension" || binding.bindingKind == "GraphExtensionFactory"

        for (dep in binding.dependencies) {
          // Unwrap the dependency key to match node IDs (Provider<X>, Lazy<X> -> X)
          // Also strip " = ..." suffix from default value keys
          val rawKey = dep.key.substringBefore(" = ")
          val targetKey = unwrapTypeKey(rawKey)

          // Check if this dependency routes through a default value node
          val defaultValueNodeKey = defaultValueNodeMap[binding.key to targetKey]

          // Check if this is an inherited scoped binding (extension accessing parent's scoped binding)
          // Note: We don't check dep.isAccessor because extension accessors may not have it set
          val isInheritedScope = isGraphExtension && targetKey in scopedKeys

          if (defaultValueNodeKey != null) {
            // Route through the synthetic default value node:
            // Consumer -> DefaultValue node -> Actual binding

            // Edge from consumer to default value node
            add(
              buildJsonObject {
                put("source", JsonPrimitive(binding.key))
                put("target", JsonPrimitive(defaultValueNodeKey))
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
            if (targetKey in metadata.bindings.map { it.key }.toSet()) {
              add(
                buildJsonObject {
                  put("source", JsonPrimitive(defaultValueNodeKey))
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
          } else {
            // Normal edge - only create link if target exists in graph
            if (targetKey !in nodeKeys) continue

            // Determine edge type for coloring
            val edgeType =
              when {
                isInheritedScope -> "inherited"
                dep.isAccessor -> "accessor"
                isAlias -> "alias"
                dep.isAssisted || (isAssistedFactory && targetKey == binding.aliasTarget) ->
                  "assisted"
                dep.isDeferrable -> "deferrable"
                isMultibinding && dep.key in multibindingSourceKeys -> "multibinding"
                else -> "normal"
              }

            // Edge value affects length in force layout (lower = shorter)
            val edgeValue = when (edgeType) {
              "alias", "assisted" -> 0.3  // Short edges for direct relationships
              else -> 1.0
            }

            add(
              buildJsonObject {
                put("source", JsonPrimitive(binding.key))
                put("target", JsonPrimitive(targetKey))
                put("edgeType", JsonPrimitive(edgeType))
                put("value", JsonPrimitive(edgeValue))

                // Apply line style based on edge type
                put(
                  "lineStyle",
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
                        put("curveness", JsonPrimitive(0.05))
                      }
                      "deferrable" -> {
                        put("color", JsonPrimitive(Colors.EDGE_DEFERRABLE))
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
                      else -> {} // use defaults
                    }
                  },
                )
              }
            )
          }
        }
      }
    }

    return buildJsonObject {
      put("nodes", nodes)
      put("links", links)
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
        "Assisted" to Colors.ASSISTED,
        "ObjectClass" to Colors.OBJECT_CLASS,
        "GraphDependency" to Colors.GRAPH_DEPENDENCY,
        "MembersInjected" to Colors.MEMBERS_INJECTED,
        "CustomWrapper" to Colors.CUSTOM_WRAPPER,
        "DefaultValue" to Colors.DEFAULT_VALUE,
        "Other" to Colors.OTHER,
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

  /**
   * Computes the longest path in the graph using DFS with memoization. Returns the binding keys in
   * order from start to end.
   *
   * This handles graphs with cycles by tracking the current path and skipping back-edges.
   */
  private fun computeLongestPath(metadata: GraphMetadata): List<String> {
    val graph = mutableMapOf<String, MutableList<String>>()

    // Initialize
    for (binding in metadata.bindings) {
      graph[binding.key] = mutableListOf()
    }

    // Build adjacency list
    // Skip deferrable (Provider/Lazy) edges since they break cycles
    for (binding in metadata.bindings) {
      for (dep in binding.dependencies) {
        if (dep.isDeferrable) continue
        val targetKey = unwrapTypeKey(dep.key)
        if (targetKey in graph) {
          graph[binding.key]?.add(targetKey)
        }
      }
    }

    // Memoization for longest path from each node
    val memo = mutableMapOf<String, List<String>>()
    val inProgress = mutableSetOf<String>() // Track nodes in current DFS path to detect cycles

    fun dfs(node: String): List<String> {
      // If we've already computed this, return cached result
      memo[node]?.let { return it }

      // If this node is already in the current path, we have a cycle - return empty
      if (node in inProgress) return emptyList()

      inProgress.add(node)

      var longestFromHere = listOf(node)

      for (neighbor in graph[node] ?: emptyList()) {
        val pathFromNeighbor = dfs(neighbor)
        if (pathFromNeighbor.isNotEmpty()) {
          val candidatePath = listOf(node) + pathFromNeighbor
          if (candidatePath.size > longestFromHere.size) {
            longestFromHere = candidatePath
          }
        }
      }

      inProgress.remove(node)
      memo[node] = longestFromHere
      return longestFromHere
    }

    // Find the longest path starting from any node
    var longestPath = emptyList<String>()
    for (node in graph.keys) {
      val path = dfs(node)
      if (path.size > longestPath.size) {
        longestPath = path
      }
    }

    return longestPath
  }

  internal companion object {
    const val NAME = "generateMetroGraphHtml"
  }
}

/** Centralized color constants for graph visualization */
internal object Colors {
  // Edge type colors
  const val EDGE_ALIAS = "#9e9e9e" // gray
  const val EDGE_ACCESSOR = "#4fc3f7" // light blue - graph entry points
  // Binding kind colors (for node fill)
  const val CONSTRUCTOR_INJECTED = "#6baed6" // blue
  const val PROVIDED = "#74c476" // green
  const val ALIAS = "#9e9e9e" // gray (synthetic)
  const val BOUND_INSTANCE = "#64b5f6" // light blue
  const val MULTIBINDING = "#f48fb1" // pink
  const val GRAPH_EXTENSION = "#b39ddb" // purple
  const val ASSISTED = "#ffab91" // peach
  const val OBJECT_CLASS = "#a5d6a7" // light green
  const val GRAPH_DEPENDENCY = "#ef9a9a" // light red
  const val MEMBERS_INJECTED = "#fff59d" // light yellow
  const val CUSTOM_WRAPPER = "#80deea" // cyan
  const val DEFAULT_VALUE = "#ffc107" // amber - default value provider
  const val OTHER = "#e0e0e0" // light gray

  // UI accent colors
  const val SCOPED_BORDER = "#ff44cc" // bright magenta
  const val GRAPH_NODE_BORDER = "#ffd700" // gold - main dependency graph
  const val EXTENSION_NODE_BORDER = "#00bfff" // deep sky blue - graph extensions
  const val LONGEST_PATH = "#f85149" // red
  const val PRIMARY = "#58a6ff" // blue link color

  // Edge type colors
  const val EDGE_DEFERRABLE = "#17becf" // cyan
  const val EDGE_ASSISTED = "#ff7f0e" // orange
  const val EDGE_INHERITED = "#ff44cc" // magenta - inherited scoped binding from parent
  const val EDGE_MULTIBINDING = "#9467bd" // purple
  const val EDGE_DEFAULT = "#30363d" // dark gray

  /** Distinct colors for package grouping */
  val packageColors =
    listOf(
      "#6baed6", // blue
      "#74c476", // green
      "#fd8d3c", // orange
      "#9e9ac8", // purple
      "#e377c2", // pink
      "#bcbd22", // olive
      "#17becf", // cyan
      "#ff7f0e", // dark orange
      "#8c564b", // brown
      "#7f7f7f", // gray
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
  val wrapperPrefixes = listOf("Provider<", "Lazy<", "javax.inject.Provider<", "jakarta.inject.Provider<")
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
 * For `com.example.Presenter.Factory` → `Presenter.Factory`
 * For `com.example.Interceptor` → `Interceptor`
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

  return if (classSegments.isNotEmpty()) classSegments.joinToString(".") else fqn.substringAfterLast('.')
}

/**
 * Extracts a display-friendly short name from a type key.
 *
 * Handles generic types like `kotlin.collections.Set<com.example.Plugin>` → `Set<Plugin>`
 * Handles nested classes like `kotlin.collections.Set<com.example.Presenter.Factory>` → `Set<Presenter.Factory>`
 * Handles annotated types like `@annotation.Foo(...) com.example.Bar` → `Bar`
 */
internal fun extractDisplayName(key: String): String {
  // Handle annotated types like "@dev.zacsweers.metro.internal.MultibindingElement(...) actual.Type"
  val actualType =
    if (key.startsWith("@") && key.contains(") ")) {
      key.substringAfter(") ")
    } else {
      key
    }

  // Check for generic types
  val genericStart = actualType.indexOf('<')
  if (genericStart != -1) {
    // Extract base type name (e.g., "Set" from "kotlin.collections.Set")
    val basePart = actualType.substring(0, genericStart)
    val baseName = extractClassName(basePart)

    // Extract and simplify type parameters, preserving nested class context
    val typeParams = actualType.substring(genericStart + 1, actualType.length - 1)
    val simplifiedParams =
      typeParams.split(',').joinToString(", ") { param ->
        extractClassName(param.trim())
      }

    return "$baseName<$simplifiedParams>"
  }

  // Non-generic: extract class name preserving nested class context
  return extractClassName(actualType)
}

/**
 * Extracts the package from a type key, handling generic types, annotated types, and nested classes.
 *
 * For `kotlin.collections.Set<com.example.Plugin>`, extracts from the type parameter: `com.example`
 * For `@annotation.Foo(...) com.example.Bar`, extracts from the actual type: `com.example`
 * For `com.example.OuterClass.InnerClass`, extracts just: `com.example`
 *
 * Uses the convention that package segments are lowercase and class names start with uppercase.
 */
internal fun extractPackage(key: String): String {
  // Handle annotated types like "@dev.zacsweers.metro.internal.MultibindingElement(...) actual.Type"
  val actualType =
    if (key.startsWith("@") && key.contains(") ")) {
      key.substringAfter(") ")
    } else {
      key
    }

  // For generic collection types, extract package from the type parameter
  val genericStart = actualType.indexOf('<')
  val typeToAnalyze = if (genericStart != -1) {
    val basePart = actualType.substring(0, genericStart)
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
