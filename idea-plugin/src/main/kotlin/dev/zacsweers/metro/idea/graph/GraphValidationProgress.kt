// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import dev.zacsweers.metro.idea.model.GraphPath

internal data class GraphValidationProgress(
  val requestPath: GraphPath,
  val graphName: String? = null,
  val completed: Int? = null,
  val total: Int? = null,
) {
  init {
    require((completed == null) == (total == null))
    require(completed == null || completed >= 0)
    require(total == null || total > 0)
    require(completed == null || total == null || completed < total)
  }

  val message: String
    get() {
      val graphName = graphName ?: return "Preparing Metro graph validation"
      val completed = completed ?: return "Validating Metro graph $graphName"
      val total = total ?: return "Validating Metro graph $graphName"
      val graphLabel = if (total == 1) "graph" else "graphs"
      return "Validating Metro graph $graphName (${completed + 1} of $total $graphLabel)"
    }

  val fraction: Double?
    get() {
      val completed = completed ?: return null
      val total = total ?: return null
      return completed.toDouble() / total
    }

  /** Progress belongs to one static or dynamic compilation and its extension descendants. */
  fun covers(path: GraphPath): Boolean {
    if (requestPath.dynamicGraphId != path.dynamicGraphId) return false
    if (requestPath.segments.size > path.segments.size) return false
    return path.segments.takeLast(requestPath.segments.size) == requestPath.segments
  }
}
