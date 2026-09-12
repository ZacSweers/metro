// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphAnalyzerTest {
  @Test
  fun `an accessor root outside the eager graph has no paths`() {
    val metadata = graph(binding("test.Value"))
    val graph = BindingGraph.from(metadata)
    assertThat(graph.graph.nodes()).contains(metadata.graph)
    assertThat(graph.eagerGraph.nodes()).doesNotContain(metadata.graph)

    val result = GraphAnalyzer(graph).computePathsToRoot()

    assertThat(result).isEqualTo(PathsToRootResult("", emptyMap()))
  }

  @Test
  fun `a recorded graph instance has an eager path only to itself`() {
    val metadata =
      graph(
        binding("test.Value"),
        binding("test.AppGraph.Impl.ChildImpl", kind = "BoundInstance"),
      )
    val result = GraphAnalyzer(BindingGraph.from(metadata)).computePathsToRoot()

    assertThat(result.rootKey).isEqualTo(metadata.graph)
    assertThat(result.paths)
      .containsExactly(
        metadata.graph,
        listOf(metadata.graph),
        "test.Value",
        emptyList<String>(),
      )
  }

  private fun graph(vararg bindings: BindingMetadata): GraphMetadata =
    GraphMetadata(
      graph = "test.AppGraph.Impl.ChildImpl",
      graphType = "test.ChildGraph",
      parentGraph = "test.AppGraph",
      scopes = emptyList(),
      aggregationScopes = emptyList(),
      roots = RootsMetadata(accessors = listOf(AccessorMetadata("test.Value", name = "value"))),
      bindings = bindings.toList(),
    )

  private fun binding(key: String, kind: String = "ConstructorInjected"): BindingMetadata =
    BindingMetadata(
      key = key,
      bindingKind = kind,
      isScoped = false,
      nameHint = key.substringAfterLast('.'),
      dependencies = emptyList(),
    )
}
