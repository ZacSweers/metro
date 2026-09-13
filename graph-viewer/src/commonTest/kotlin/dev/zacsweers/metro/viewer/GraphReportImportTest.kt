// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.viewer

import dev.zacsweers.metro.gradle.analysis.AggregatedGraphMetadata
import dev.zacsweers.metro.gradle.analysis.BindingMetadata
import dev.zacsweers.metro.gradle.analysis.CentralityResult
import dev.zacsweers.metro.gradle.analysis.DependencyMetadata
import dev.zacsweers.metro.gradle.analysis.DominatorResult
import dev.zacsweers.metro.gradle.analysis.FanAnalysisResult
import dev.zacsweers.metro.gradle.analysis.FanScore
import dev.zacsweers.metro.gradle.analysis.FullAnalysisReport
import dev.zacsweers.metro.gradle.analysis.GraphAnalysis
import dev.zacsweers.metro.gradle.analysis.GraphMetadata
import dev.zacsweers.metro.gradle.analysis.GraphReportRenderer
import dev.zacsweers.metro.gradle.analysis.GraphStatistics
import dev.zacsweers.metro.gradle.analysis.LongestPathResult
import dev.zacsweers.metro.gradle.analysis.analysisEdges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class GraphReportImportTest {
  private val graph = graph("AppGraph")

  @Test
  fun `partial analysis leaves unmeasured metrics absent`() {
    val imported = GraphReportImport(listOf(file(graph), analysisFile(graph)))
    val nodes = imported.renderGraph(graph.graph).getValue("nodes").jsonArray
    val input = nodes.map { it.jsonObject }.single { it["fullKey"] == JsonPrimitive("Application") }

    assertEquals(JsonPrimitive(0), input["fanIn"])
    assertEquals(JsonPrimitive(0), input["fanOut"])
    assertFalse("centrality" in input)
    assertFalse("dominatorCount" in input)
  }

  @Test
  fun `raw and aggregated reports produce the same viewer data`() {
    val raw = GraphReportImport(listOf(file(graph)))
    val aggregate = AggregatedGraphMetadata(":app", 1, listOf(graph))
    val combined =
      GraphReportImport(listOf(ReportFile("graphMetadata.json", Json.encodeToString(aggregate))))

    assertEquals(raw.summary, combined.summary)
    assertEquals(raw.renderGraph(graph.graph), combined.renderGraph(graph.graph))
    assertEquals(
      GraphReportRenderer(graphs = listOf(graph)).buildData(graph),
      raw.renderGraph(graph.graph),
    )
    assertFalse(raw.summary.hasAnalysis)
  }

  @Test
  fun `identical reports are deduplicated`() {
    val imported = GraphReportImport(listOf(file(graph), file(graph)))

    assertEquals(1, imported.summary.graphs.size)
  }

  @Test
  fun `multiple implementations of one graph type have distinct labels`() {
    val first = graph("AppGraph.SessionImpl").copy(graphType = "SessionGraph")
    val second = graph("OtherGraph.SessionImpl").copy(graphType = "SessionGraph")
    val imported = GraphReportImport(listOf(file(first), file(second)))

    assertEquals(listOf(first.graph, second.graph), imported.summary.graphs.map { it.label })
  }

  @Test
  fun `conflicting definitions identify the graph and file`() {
    val conflict = graph.copy(scopes = listOf("DifferentScope"))
    val error =
      assertFailsWith<IllegalArgumentException> {
        GraphReportImport(listOf(file(graph), file(conflict)))
      }

    assertTrue(error.message.orEmpty().contains("AppGraph.json: Conflicting reports for AppGraph"))
  }

  @Test
  fun `members injection and a supplied instance can share a key`() {
    val binding = graph.bindings.single()
    val injected = binding.copy(bindingKind = "MembersInjected")
    val report = graph.copy(bindings = listOf(binding, injected))

    assertEquals(2, GraphReportImport(listOf(file(report))).summary.graphs.single().bindingCount)
  }

  @Test
  fun `duplicate binding definitions are rejected`() {
    val invalid = graph.copy(bindings = graph.bindings + graph.bindings)

    assertFailsWith<IllegalArgumentException> { GraphReportImport(listOf(file(invalid))) }
  }

  @Test
  fun `different ordinary binding kinds cannot claim the same key`() {
    val binding = graph.bindings.single()
    val invalid = graph.copy(bindings = listOf(binding, binding.copy(bindingKind = "Provided")))

    assertFailsWith<IllegalArgumentException> { GraphReportImport(listOf(file(invalid))) }
  }

  @Test
  fun `missing parent and included graph reports are listed`() {
    val child =
      graph("SessionGraph", parent = "AppGraph").copy(includedGraphKeys = listOf("ConfigGraph"))
    val imported = GraphReportImport(listOf(file(child)))

    assertEquals(2, imported.summary.warnings.size)
    assertTrue(imported.summary.warnings.any { "parent graph AppGraph" in it })
    assertTrue(imported.summary.warnings.any { "included graph report ConfigGraph" in it })
  }

  @Test
  fun `missing extension warnings disappear after adding its report`() {
    val factory =
      graph.bindings
        .single()
        .copy(bindingKind = "GraphExtensionFactory", extensionType = "SessionGraph")
    val parent = graph.copy(bindings = listOf(factory))
    val child =
      graph("AppGraph.Impl.SessionGraphImpl", parent = "AppGraph").copy(graphType = "SessionGraph")

    assertEquals(1, GraphReportImport(listOf(file(parent))).summary.warnings.size)
    assertTrue(GraphReportImport(listOf(file(parent), file(child))).summary.warnings.isEmpty())
  }

  @Test
  fun `non factory extensions also report missing children`() {
    val extension =
      graph.bindings.single().copy(key = "DetailsGraph", bindingKind = "GraphExtension")
    val parent = graph.copy(bindings = listOf(extension))

    assertTrue(
      GraphReportImport(listOf(file(parent))).summary.warnings.single().contains("DetailsGraph")
    )
  }

  @Test
  fun `an extension under another parent does not satisfy a missing report`() {
    val extension =
      graph.bindings.single().copy(key = "SessionGraph", bindingKind = "GraphExtension")
    val first = graph.copy(bindings = listOf(extension))
    val other = graph("OtherGraph")
    val child =
      graph("OtherGraph.SessionImpl", parent = other.graph).copy(graphType = "SessionGraph")
    val imported = GraphReportImport(listOf(file(first), file(other), file(child)))

    assertTrue(
      imported.summary.warnings.single().contains("AppGraph: extension report SessionGraph")
    )
  }

  @Test
  fun `qualifiers on included graph inputs do not hide available reports`() {
    val consumer = graph.copy(includedGraphKeys = listOf("@Named(\"primary\") ConfigGraph"))
    val imported = GraphReportImport(listOf(file(consumer), file(graph("ConfigGraph"))))

    assertTrue(imported.summary.warnings.isEmpty())
  }

  @Test
  fun `parent cycles fail before conversion`() {
    val parent = graph.copy(parentGraph = "SessionGraph")
    val child = graph("SessionGraph", parent = "AppGraph")

    assertFailsWith<IllegalArgumentException> {
      GraphReportImport(listOf(file(parent), file(child)))
    }
  }

  @Test
  fun `invalid JSON includes the filename`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        GraphReportImport(listOf(ReportFile("broken.json", "{")))
      }

    assertTrue(error.message.orEmpty().startsWith("broken.json:"))
  }

  @Test
  fun `unrelated JSON and empty imports are rejected`() {
    for (content in listOf("[]", "{}", "null", "{\"graphs\":[]}")) {
      assertFailsWith<IllegalArgumentException> {
        GraphReportImport(listOf(ReportFile("input.json", content)))
      }
    }
    assertFailsWith<IllegalArgumentException> { GraphReportImport(emptyList()) }
  }

  @Test
  fun `unknown format versions are rejected`() {
    val document = Json.decodeFromString<JsonObject>(file(graph).content)
    val future = JsonObject(document + ("formatVersion" to JsonPrimitive(99)))

    assertFailsWith<IllegalArgumentException> {
      GraphReportImport(listOf(ReportFile("future.json", future.toString())))
    }
  }

  @Test
  fun `version and graph count fields must be numeric integers`() {
    val document = Json.decodeFromString<JsonObject>(file(graph).content)
    val stringVersion = JsonObject(document + ("formatVersion" to JsonPrimitive("1")))
    assertFailsWith<IllegalArgumentException> {
      GraphReportImport(listOf(ReportFile("version.json", stringVersion.toString())))
    }
    val aggregate = Json.encodeToString(AggregatedGraphMetadata(":app", 1, listOf(graph)))
    for (invalid in listOf("\"1\"", "{}", "1.5")) {
      val broken = aggregate.replace("\"graphCount\":1", "\"graphCount\":$invalid")
      assertFailsWith<IllegalArgumentException> {
        GraphReportImport(listOf(ReportFile("count.json", broken)))
      }
    }
  }

  @Test
  fun `analysis may contain the graph instance added by the analyzer`() {
    val imported = GraphReportImport(listOf(file(graph), analysisFile(graph)))

    assertTrue(imported.summary.hasAnalysis)
    assertEquals(JsonPrimitive(true), imported.renderGraph(graph.graph)["hasAnalysis"])
  }

  @Test
  fun `analysis is optional for each graph`() {
    val other = graph("OtherGraph")
    val imported = GraphReportImport(listOf(file(graph), file(other), analysisFile(graph)))

    assertEquals(JsonPrimitive(false), imported.renderGraph(other.graph)["hasAnalysis"])
  }

  @Test
  fun `analysis for graphs outside the import is skipped with a warning`() {
    val other = graph("OtherGraph")
    val imported = GraphReportImport(listOf(file(graph), analysisFile(graph), analysisFile(other)))

    assertTrue(imported.summary.hasAnalysis)
    assertTrue(imported.summary.warnings.single().contains("OtherGraph"))
  }

  @Test
  fun `stale analysis with matching graph names is rejected`() {
    val changed = graph.copy(bindings = listOf(graph.bindings.single().copy(key = "Changed")))

    assertFailsWith<IllegalArgumentException> {
      GraphReportImport(listOf(file(changed), analysisFile(graph)))
    }
  }

  @Test
  fun `stale dependencies are rejected even when binding keys match`() {
    val service = graph.bindings.single().copy(key = "Service", bindingKind = "ConstructorInjected")
    val previous = graph.copy(bindings = graph.bindings + service)
    val changed = service.copy(dependencies = listOf(DependencyMetadata("Application", false)))
    val current = graph.copy(bindings = graph.bindings + changed)

    val error =
      assertFailsWith<IllegalArgumentException> {
        GraphReportImport(listOf(file(current), analysisFile(previous)))
      }
    assertTrue(error.message.orEmpty().contains("Analysis dependencies do not match"))
  }

  @Test
  fun `fan counts are accepted when the analyzer leaves connection lists empty`() {
    val service =
      graph.bindings
        .single()
        .copy(key = "Service", dependencies = listOf(DependencyMetadata("Application", false)))
    val report = graph.copy(bindings = graph.bindings + service)
    val imported =
      GraphReportImport(
        listOf(file(report), analysisFile(report, listOf("Service", "Application")))
      )

    assertTrue(imported.summary.hasAnalysis)
  }

  @Test
  fun `the longest eager chain cannot contain a deferred dependency`() {
    val service =
      graph.bindings
        .single()
        .copy(
          key = "Service",
          dependencies = listOf(DependencyMetadata("Application", false, "Provider")),
        )
    val report = graph.copy(bindings = graph.bindings + service)
    val error =
      assertFailsWith<IllegalArgumentException> {
        GraphReportImport(
          listOf(file(report), analysisFile(report, listOf("Service", "Application")))
        )
      }

    assertTrue(error.message.orEmpty().contains("longest chain"))
  }

  @Test
  fun `analysis requires matching metadata`() {
    assertFailsWith<IllegalArgumentException> { GraphReportImport(listOf(analysisFile(graph))) }
    assertFailsWith<IllegalArgumentException> {
      GraphReportImport(listOf(file(graph("OtherGraph")), analysisFile(graph)))
    }
  }

  @Test
  fun `new import has no state from the previous import`() {
    val first = GraphReportImport(listOf(file(graph)))
    val other = graph("OtherGraph")
    val second = GraphReportImport(listOf(file(other)))

    assertEquals(listOf(graph.graph), first.summary.graphs.map { it.name })
    assertEquals(listOf(other.graph), second.summary.graphs.map { it.name })
    assertFailsWith<IllegalArgumentException> { second.renderGraph(graph.graph) }
  }

  private fun graph(name: String, parent: String? = null): GraphMetadata =
    GraphMetadata(
      graph = name,
      scopes = emptyList(),
      aggregationScopes = emptyList(),
      parentGraph = parent,
      bindings =
        listOf(
          BindingMetadata(
            "Application",
            "BoundInstance",
            isScoped = false,
            nameHint = "application",
            dependencies = emptyList(),
            isGraphInput = true,
          )
        ),
    )

  private fun file(graph: GraphMetadata): ReportFile =
    ReportFile("${graph.graph}.json", Json.encodeToString(graph))

  private fun analysisFile(graph: GraphMetadata, path: List<String> = emptyList()): ReportFile {
    val keys = graph.bindings.map { it.key } + graph.graph
    val edges = graph.analysisEdges()
    val fans = keys.map { key ->
      val fanIn = edges.filter { it.target == key }.map { it.source }.distinct().size
      val fanOut = edges.filter { it.source == key }.map { it.target }.distinct().size
      FanScore(key, "BoundInstance", fanIn, fanOut, emptyList(), emptyList())
    }
    val analysis =
      GraphAnalysis(
        graphName = graph.graph,
        statistics = GraphStatistics(keys.size, 0, keys.size, emptyMap(), 0.0, 0, null, 0, 0, 0, 0),
        longestPath = LongestPathResult(path.size, listOf(path), 0.0, emptyMap()),
        dominator = DominatorResult(emptyList()),
        centrality = CentralityResult(emptyList()),
        fanAnalysis = FanAnalysisResult(fans, emptyList(), emptyList(), 0.0, 0.0),
      )
    return ReportFile(
      "analysis.json",
      Json.encodeToString(FullAnalysisReport(":app", listOf(analysis))),
    )
  }
}
