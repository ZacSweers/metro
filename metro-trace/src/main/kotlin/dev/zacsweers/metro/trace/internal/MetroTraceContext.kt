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
   * The visible section name is derived from [qualifier] and the requested [contextualType],
   * falling back to [type] when the contextual type is the same as the canonical type.
   */
  public inline fun <T> trace(
    qualifier: String?,
    type: String,
    contextualType: String?,
    kind: String?,
    crossinline block: () -> T,
  ): T {
    val renderedContextualType = contextualType?.takeIf { it != type }
    val nameType = renderedContextualType ?: type
    val name =
      if (qualifier == null) {
        nameType
      } else {
        "$qualifier $nameType"
      }
    return tracer.trace(
      category = category,
      name = name,
      metadataBlock = {
        addMetadataEntry("metro.graph", graphName)
        addMetadataEntry("metro.graph_path", graphPath)
        addMetadataEntry("metro.type", type)
        renderedContextualType?.let { addMetadataEntry("metro.contextual_type", it) }
        qualifier?.let { addMetadataEntry("metro.qualifier", it) }
        kind?.let { addMetadataEntry("metro.binding_kind", it) }
      },
    ) {
      block()
    }
  }

  /**
   * Traces a suspending [block] with Metro-specific metadata.
   *
   * Unlike [trace], this uses the tracer's coroutine-aware section API so the recorded section
   * stays attached to the coroutine across suspension points and thread hops rather than being
   * split or misattributed by thread-bound begin/end pairs.
   *
   * `traceCoroutine` installs a propagation token into the coroutine context, so sections opened by
   * child coroutines launched inside [block] (e.g. `coroutineScope { async { … } }` in generated
   * parallel-resolution code) parent to this section automatically. Manual token propagation is
   * only needed when execution leaves structured concurrency (executors, detached scopes), which
   * Metro-generated code currently never does.
   */
  public suspend inline fun <T> traceSuspend(
    qualifier: String?,
    type: String,
    contextualType: String?,
    kind: String?,
    crossinline block: suspend () -> T,
  ): T {
    val renderedContextualType = contextualType?.takeIf { it != type }
    val nameType = renderedContextualType ?: type
    val name =
      if (qualifier == null) {
        nameType
      } else {
        "$qualifier $nameType"
      }
    return tracer.traceCoroutine(
      category = category,
      name = name,
      metadataBlock = {
        addMetadataEntry("metro.graph", graphName)
        addMetadataEntry("metro.graph_path", graphPath)
        addMetadataEntry("metro.type", type)
        renderedContextualType?.let { addMetadataEntry("metro.contextual_type", it) }
        qualifier?.let { addMetadataEntry("metro.qualifier", it) }
        kind?.let { addMetadataEntry("metro.binding_kind", it) }
      },
    ) {
      block()
    }
  }

  /**
   * Emits a zero-duration event with Metro-specific metadata.
   *
   * The visible event [name] describes the generated graph entry point. [callable] records the
   * callable name without the graph prefix, and the remaining metadata records the requested key.
   */
  public fun instant(
    name: String,
    callable: String,
    qualifier: String?,
    type: String,
    contextualType: String?,
    kind: String?,
  ) {
    val renderedContextualType = contextualType?.takeIf { it != type }
    tracer.instant(
      category = category,
      name = name,
      metadataBlock = {
        addMetadataEntry("metro.graph", graphName)
        addMetadataEntry("metro.graph_path", graphPath)
        addMetadataEntry("metro.callable", callable)
        addMetadataEntry("metro.type", type)
        renderedContextualType?.let { addMetadataEntry("metro.contextual_type", it) }
        qualifier?.let { addMetadataEntry("metro.qualifier", it) }
        kind?.let { addMetadataEntry("metro.entry_point_kind", it) }
      },
    )
  }
}
