// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace

/**
 * A populated graph ready for analysis.
 *
 * Preparation resolves bindings, captures edge kinds, and assigns vertex ids on the calling thread.
 * A worker only needs [analysisInput], which holds no keys. Pass it to [analyzePreparedGraph] and
 * hand the output to [finish] on the preparing thread. [finish] maps ids back to keys, reports
 * diagnostics, validates bindings, and assigns binding indices.
 */
public abstract class PreparedGraphSeal<TypeKey> internal constructor() {
  /** The graph as primitive data. Any thread can analyze it. */
  public abstract val analysisInput: AnalysisInput

  /** Analyzes [analysisInput] on the calling thread. */
  context(traceScope: TraceScope)
  public abstract fun analyze(): GraphAnalysis<TypeKey>

  /** Applies this graph's analysis result and runs its callbacks on the preparing thread. */
  context(traceScope: TraceScope)
  public abstract fun finish(analysis: GraphAnalysis<TypeKey>): GraphTopology<TypeKey>

  /** Applies the result of [analyzePreparedGraph] for [analysisInput] on the preparing thread. */
  context(traceScope: TraceScope)
  public abstract fun finish(output: AnalysisOutput): GraphTopology<TypeKey>
}

/** Analyzes a [PreparedGraphSeal.analysisInput]. Safe to call on any thread. */
context(traceScope: TraceScope)
public fun analyzePreparedGraph(
  input: AnalysisInput,
  ensureActive: () -> Unit = {},
): AnalysisOutput =
  trace("Sort and validate") { trace("Topo sort") { analyze(input, ensureActive) } }

/**
 * An analysis result owned by the [PreparedGraphSeal] that produced it. Its key views are mapped
 * from [output] on each read, so read them on the preparing thread.
 */
public class GraphAnalysis<TypeKey>
internal constructor(
  internal val output: AnalysisOutput,
  private val keysOf: (IntArray) -> List<TypeKey>,
  private val topologyOf: (AnalysisOutput) -> GraphTopology<TypeKey>,
) {
  internal val topology: GraphTopology<TypeKey>?
    get() {
      if (output.hardCycle != null) {
        return null
      }
      return topologyOf(output)
    }

  internal val hardCycle: List<TypeKey>?
    get() = output.hardCycle?.let(keysOf)

  internal val sortedCycles: List<List<TypeKey>>
    get() = output.softCycles.map(keysOf)
}
