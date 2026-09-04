// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.reporting

import com.google.common.truth.Truth.assertThat
import okio.utf8Size
import org.junit.Test

class GraphReportFileNameTest {
  @Test
  fun `ordinary graph names retain existing filenames`() {
    assertThat(graphReportFileName("example.AppGraph", "json", "graph-"))
      .isEqualTo("graph-example-AppGraph.json")
    assertThat(graphReportFileName("example.AppGraph", "html")).isEqualTo("example-AppGraph.html")
  }

  @Test
  fun `long graph names fit within the UTF-8 filename limit`() {
    for (graphName in listOf("ChildGraph".repeat(40), "図".repeat(100))) {
      val fileName = graphReportFileName(graphName, "json", "graph-")
      assertThat(fileName.utf8Size()).isAtMost(255)
      assertThat(fileName).startsWith("graph-")
      assertThat(fileName).endsWith(".json")
      assertThat(fileName).isNotEqualTo(graphReportFileName(graphName + "Child", "json", "graph-"))
    }
  }
}
