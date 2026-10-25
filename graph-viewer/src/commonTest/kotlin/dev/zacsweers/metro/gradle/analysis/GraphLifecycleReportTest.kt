// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GraphLifecycleReportTest {
  @Test
  fun callbacksRemainSeparateFromTheirParameterEdges() {
    val open = LifecycleCallbackMetadata(LifecyclePhase.INIT, "AppGraph.start")
    val close =
      LifecycleCallbackMetadata(
        LifecyclePhase.CLOSE,
        "AppGraph.stop",
        parameters =
          listOf(LifecycleParameterMetadata("service", DependencyMetadata("Service", false))),
      )
    val graph =
      GraphMetadata(
        graph = "AppGraph",
        key = "AppGraph",
        scopes = emptyList(),
        aggregationScopes = emptyList(),
        roots = RootsMetadata(lifecycleCallbacks = listOf(open, close)),
        bindings =
          listOf(
            BindingMetadata(
              "Service",
              "Provided",
              isScoped = false,
              nameHint = "Service",
              dependencies = emptyList(),
            ),
          ),
      )
    assertEquals(listOf(AnalysisEdge("AppGraph", "Service", eager = false)), graph.analysisEdges())
    val data = GraphReportRenderer().buildData(graph)
    val roots =
      data
        .getValue("nodes")
        .jsonArray
        .map { it.jsonObject }
        .filter { it["isRootMember"] == JsonPrimitive(true) }
    assertEquals(
      setOf("OnGraphInit", "OnGraphClose"),
      roots.map { it.getValue("kind").jsonPrimitive.content }.toSet(),
    )
    val links = data.getValue("links").jsonArray.map { it.jsonObject }
    assertTrue(
      links.any {
        it["parameter"] == JsonPrimitive("service") && it["target"] == JsonPrimitive("Service")
      },
    )
  }
}
