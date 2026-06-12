// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer

/**
 * Structured body content for a [MetroDiagnostic].
 *
 * Sections render in declaration order, separated by blank lines. Each section type describes the
 * content it carries; [DiagnosticRenderer] owns the actual layout.
 */
internal sealed interface DiagnosticSection {

  /**
   * A compact dependency path such as `AppGraph.repo -> RepositoryImpl -> Dependency`.
   *
   * The renderer keeps each arrow and target together, so wrapped chains break between steps.
   */
  data class Chain(val items: List<Text>) : DiagnosticSection {
    init {
      require(items.isNotEmpty()) { "Chain requires at least one item" }
    }
  }

  /**
   * A binding trace showing how the graph reached a requested key.
   *
   * [graphName] is rendered once in the section header. Entries only set their own graph when it
   * differs from that header. [continuation] is set by [DiagnosticBatch] when another diagnostic in
   * the same batch already printed the shared tail of this trace.
   */
  data class BindingTrace(
    val graphName: String,
    val entries: List<TraceEntry>,
    val continuation: Text? = null,
  ) : DiagnosticSection

  /** A dependency cycle, drawn as a closed loop. */
  data class Cycle(val nodes: List<CycleNode>) : DiagnosticSection {
    init {
      require(nodes.isNotEmpty()) { "Cycle requires at least one node" }
    }
  }

  /** Source locations with optional code excerpts, used for diagnostics such as duplicates. */
  data class Locations(val header: Text?, val items: List<LocatedItem>) : DiagnosticSection

  /** Near-miss bindings for a missing binding diagnostic. */
  data class SimilarBindings(val items: List<SimilarBindingItem>) : DiagnosticSection

  /** Preformatted code (typically an IR-rendered signature). Never wrapped or restyled. */
  data class CodeBlock(val code: String, val location: String? = null) : DiagnosticSection

  /** Free-form prose for content with no better-fitting section type. */
  data class Generic(val text: Text) : DiagnosticSection
}

internal data class TraceEntry(
  val key: Text,
  /** Usage phrase such as "is injected at" or "is requested at". */
  val usage: String?,
  /** Usage site such as `RepositoryImpl(…, dep)`. */
  val context: Text?,
  /** Set only when this entry belongs to a different graph than the trace header. */
  val graphName: String? = null,
)

internal data class CycleNode(
  val name: Text,
  /** True when the edge from this node to the next is a `@Binds` alias rather than a dependency. */
  val aliasEdgeToNext: Boolean = false,
)

internal data class LocatedItem(
  val location: String?,
  /** Preformatted code excerpt (IR-rendered signature), possibly multi-line. */
  val code: String?,
  val description: Text? = null,
  /**
   * Resolved source span, when known. Snippet-rendering profiles use this to draw a source frame;
   * other profiles fall back to [location] and [code].
   */
  val span: DiagnosticSpan? = null,
)

internal data class SimilarBindingItem(
  val key: Text,
  /** Why it's similar but not a match, e.g. "same type, different qualifier". */
  val description: String,
  val location: String?,
)
