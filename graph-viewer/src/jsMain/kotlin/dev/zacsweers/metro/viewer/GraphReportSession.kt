// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.viewer

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.json.Json

/** Browser-facing session containing imported report data. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class GraphReportSession(filesJson: String) {
  private val reports = GraphReportImport(Json.decodeFromString<List<ReportFile>>(filesJson))

  public val summaryJson: String = Json.encodeToString(ReportSummary.serializer(), reports.summary)

  public fun renderGraph(name: String): String = reports.renderGraph(name).toString()
}
