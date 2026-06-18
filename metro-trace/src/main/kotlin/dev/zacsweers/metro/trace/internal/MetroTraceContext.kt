// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.trace.internal

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
   * The visible section name is derived from [qualifier] and [binding].
   */
  public inline fun <T> trace(
    qualifier: String?,
    binding: String,
    kind: String?,
    crossinline block: () -> T,
  ): T {
    val name =
      if (qualifier == null) {
        binding
      } else {
        "$qualifier $binding"
      }
    return tracer.trace(
      category = category,
      name = name,
      metadataBlock = {
        addMetadataEntry("metro.graph", graphName)
        addMetadataEntry("metro.graph_path", graphPath)
        addMetadataEntry("metro.binding", binding)
        qualifier?.let { addMetadataEntry("metro.qualifier", it) }
        kind?.let { addMetadataEntry("metro.binding_kind", it) }
      },
    ) {
      block()
    }
  }
}
