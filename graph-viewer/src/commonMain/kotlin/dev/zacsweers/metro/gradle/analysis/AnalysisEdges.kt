// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi

/** An edge in the recorded graph used for analysis. */
@ExperimentalMetroGradleApi
public data class AnalysisEdge(val source: String, val target: String, val eager: Boolean)

/** Resolves recorded dependencies and accessor edges for graph analysis. */
@ExperimentalMetroGradleApi
public fun GraphMetadata.analysisEdges(): List<AnalysisEdge> = buildList {
  val bindingKeys = bindings.mapTo(mutableSetOf()) { it.key }
  for (binding in bindings) {
    for (dependency in binding.dependencies) {
      val target = unwrapTypeKey(dependency.key)
      if (target in bindingKeys) {
        add(AnalysisEdge(binding.key, target, !dependency.isDeferrable))
      }
    }
  }
  for (accessor in roots?.accessors.orEmpty()) {
    val target = unwrapTypeKey(accessor.key)
    if (target in bindingKeys) {
      add(AnalysisEdge(graph, target, eager = false))
    }
  }
}
