// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.graph.GraphTopology
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey

/** The outcome of attempting to seal one graph. */
internal sealed interface KaGraphValidationResult {
  val context: GraphContext

  val graph: KaGraphDeclaration
    get() = context.graph

  /** Validation produced a normal Metro result, including any diagnostics it found. */
  class Completed(
    override val context: GraphContext,
    val diagnostics: List<KaGraphDiagnostic>,
    /** Null when a fatal Metro diagnostic aborted the seal before sorting. */
    val topology: GraphTopology<KaTypeKey>?,
    val bindings: ScatterMap<KaTypeKey, KaBinding>,
    /** Bindings that directly or transitively require suspend initialization. */
    val suspendKeys: Set<KaTypeKey>,
    /** Bindings this context delegates to its immediate parent for validation. */
    val parentReservations: Map<KaTypeKey, KaBinding> = emptyMap(),
  ) : KaGraphValidationResult

  /** The IDE reached an analysis limit and cannot claim that the graph is fully validated. */
  class Incomplete(override val context: GraphContext, val reason: String) : KaGraphValidationResult

  /** Validation stopped because the IDE plugin itself failed unexpectedly. */
  class InternalError(override val context: GraphContext, val cause: Throwable) :
    KaGraphValidationResult
}

/** Expected control flow when a selected implicit binding crosses an IDE analysis boundary. */
internal class IncompleteGraphAnalysis(val reason: String) : RuntimeException(reason) {
  override fun fillInStackTrace(): Throwable = this
}
