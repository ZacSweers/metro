// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.tracing.TraceScope

/**
 * A populated graph ready for analysis on a worker.
 *
 * Preparation resolves bindings and captures edge kinds on the calling thread. Key comparison and
 * hashing must be safe for worker reads. Call [finish] on the preparing thread after analysis
 * completes to report diagnostics, validate bindings, and assign binding indices.
 */
public abstract class PreparedGraphSeal<TypeKey> internal constructor() {
  /** Computes topology and cycle paths using the prepared graph data. */
  context(traceScope: TraceScope)
  public abstract fun analyze(): GraphAnalysis<TypeKey>

  /** Applies this graph's analysis result and runs its callbacks on the preparing thread. */
  context(traceScope: TraceScope)
  public abstract fun finish(analysis: GraphAnalysis<TypeKey>): GraphTopology<TypeKey>
}

/** An analysis result owned by the [PreparedGraphSeal] that produced it. */
public class GraphAnalysis<TypeKey>
internal constructor(
  internal val topology: GraphTopology<TypeKey>?,
  internal val hardCycle: List<TypeKey>?,
  internal val sortedCycles: List<List<TypeKey>>,
)
