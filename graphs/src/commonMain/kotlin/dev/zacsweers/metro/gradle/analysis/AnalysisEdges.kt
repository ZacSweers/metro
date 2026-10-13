// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.graph.ExperimentalMetroGraphApi

/** An edge in the recorded graph used for analysis. */
@ExperimentalMetroGraphApi
public data class AnalysisEdge(val source: String, val target: String, val eager: Boolean)

/** Resolves recorded dependencies and accessor edges for graph analysis. */
@ExperimentalMetroGraphApi
public fun GraphMetadata.analysisEdges(): List<AnalysisEdge> = buildList {
  val bindingKeys = bindings.mapTo(mutableSetOf()) { it.key }
  for (binding in bindings) {
    for (dependency in binding.dependencies) {
      if (dependency.key in bindingKeys) {
        add(AnalysisEdge(binding.key, dependency.key, !dependency.isDeferrable))
      }
    }
  }
  for (accessor in roots?.accessors.orEmpty()) {
    if (accessor.key in bindingKeys) {
      add(AnalysisEdge(key, accessor.key, eager = false))
    }
  }
}
