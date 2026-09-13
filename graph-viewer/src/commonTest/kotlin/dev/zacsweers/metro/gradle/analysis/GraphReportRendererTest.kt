// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class GraphReportRendererTest {
  @Test
  fun missingAnalysisLeavesMeasurementsAbsent() {
    val graph =
      GraphMetadata(
        graph = "AppGraph",
        scopes = emptyList(),
        aggregationScopes = emptyList(),
        bindings =
          listOf(
            BindingMetadata(
              "Service",
              "ConstructorInjected",
              isScoped = false,
              nameHint = "Service",
              dependencies = emptyList(),
            )
          ),
      )
    val data = GraphReportRenderer().buildData(graph)
    assertEquals(JsonPrimitive(false), data["hasAnalysis"])
    for (node in data.getValue("nodes").jsonArray) {
      for (field in listOf("fanIn", "fanOut", "centrality", "dominatorCount")) {
        assertFalse(field in node.jsonObject)
      }
    }
  }

  @Test
  fun childReportConnectsToItsScopedAncestorBinding() {
    val parent =
      GraphMetadata(
        graph = "AppGraph",
        scopes = listOf("AppScope"),
        aggregationScopes = emptyList(),
        bindings =
          listOf(
            BindingMetadata(
              "State",
              "ConstructorInjected",
              isScoped = true,
              nameHint = "State",
              dependencies = emptyList(),
            ),
            BindingMetadata(
              "ChildGraph",
              "GraphExtension",
              isScoped = false,
              nameHint = "ChildGraph",
              dependencies = emptyList(),
            ),
          ),
      )
    val child =
      GraphMetadata(
        graph = "AppGraph.Impl.ChildGraphImpl",
        graphType = "ChildGraph",
        parentGraph = parent.graph,
        scopes = emptyList(),
        aggregationScopes = emptyList(),
        roots =
          RootsMetadata(
            accessors = listOf(AccessorMetadata("Service", name = "service", isProperty = true))
          ),
        bindings =
          listOf(
            BindingMetadata(
              "AppGraph",
              "BoundInstance",
              isScoped = false,
              nameHint = "AppGraph",
              dependencies = emptyList(),
              isGraphInput = false,
            ),
            BindingMetadata(
              "State",
              "GraphDependency",
              isScoped = false,
              nameHint = "State",
              dependencies = listOf(DependencyMetadata("AppGraph", false)),
              graphDependency = GraphDependencyMetadata("AppGraph", "AppGraph", fromParent = true),
            ),
            BindingMetadata(
              "Service",
              "ConstructorInjected",
              isScoped = false,
              nameHint = "Service",
              dependencies = listOf(DependencyMetadata("State", false)),
            ),
          ),
      )
    val data = GraphReportRenderer(graphs = listOf(parent, child)).buildData(child)
    val states =
      data
        .getValue("nodes")
        .jsonArray
        .map { it.jsonObject }
        .filter { it["fullKey"] == JsonPrimitive("State") }
    assertEquals(1, states.size)
    assertEquals(JsonPrimitive("graph:AppGraph"), states.single()["regionId"])
    assertEquals(JsonPrimitive("graph:${child.graph}"), data["initialRegionId"])
    val dependency =
      data
        .getValue("links")
        .jsonArray
        .map { it.jsonObject }
        .single { it["source"] == JsonPrimitive("Service") }
    assertEquals(states.single()["id"], dependency["target"])
    assertEquals(JsonPrimitive(true), dependency["parentDependency"])
    assertTrue(dependency.getValue("inheritedVia").jsonArray.isNotEmpty())
  }

  @Test
  fun qualifiedNestedTypesKeepTheSameDisplayNames() {
    val key =
      "@test.Named(\"a ) value\") kotlin.collections.Map<first.Item, test.Presenter.Factory>"
    val names = typeDisplayNames(listOf(key, "second.Item"))
    assertEquals("Map<first.Item, Presenter.Factory>", extractDisplayName(key, names))
    assertEquals("first", extractPackage(key))
  }
}
