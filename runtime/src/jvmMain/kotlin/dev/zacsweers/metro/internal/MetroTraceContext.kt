// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import androidx.tracing.Tracer

/**
 * Immutable tracing state used by Metro-generated code for a dependency graph.
 *
 * This stores only stable naming and metadata values. It intentionally does not track a current
 * section or propagation token because generated graphs and providers may be reused from multiple
 * threads.
 *
 * @param tracer the AndroidX tracer supplied by the user's graph.
 * @param category the trace category used for Metro runtime trace sections.
 * @param graphName the current graph or graph extension name.
 * @param graphPath the slash-separated path from the root graph to the current graph.
 */
public class MetroTraceContext(
  public val tracer: Tracer,
  public val category: String,
  public val graphName: String,
  public val graphPath: String,
) {
  /** Returns a child context for a graph extension while reusing the same underlying tracer. */
  public fun child(graphName: String): MetroTraceContext {
    val childPath =
      if (graphPath.isEmpty()) {
        graphName
      } else {
        "$graphPath/$graphName"
      }
    return MetroTraceContext(
      tracer = tracer,
      category = category,
      graphName = graphName,
      graphPath = childPath,
    )
  }

  /**
   * Traces [block] with Metro-specific metadata.
   *
   * The [name] should be a short, human-readable binding name suitable for scanning in Perfetto.
   * The remaining parameters are recorded as structured metadata when available.
   */
  public inline fun <T> trace(
    name: String,
    qualifier: String? = null,
    binding: String? = null,
    kind: String? = null,
    crossinline block: () -> T,
  ): T {
    return tracer.trace(
      category = category,
      name = name,
      metadataBlock = {
        addMetadataEntry("metro.graph", graphName)
        addMetadataEntry("metro.graph_path", graphPath)
        binding?.let { addMetadataEntry("metro.binding", it) }
        qualifier?.let { addMetadataEntry("metro.qualifier", it) }
        kind?.let { addMetadataEntry("metro.binding_kind", it) }
      },
    ) {
      block()
    }
  }
}
