// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A [CloseableGraph] alternative whose generated [close] can suspend, so its `@OnGraphClose`
 * callbacks can too. Requires Metro's suspend provider support.
 */
@ExperimentalMetroApi
@ExperimentalMetroCoroutinesApi
public interface SuspendCloseableGraph {
  /**
   * Closes the graph's extensions and runs its close callbacks. Only the first call does anything.
   * Cancelling the caller doesn't stop cleanup partway through.
   *
   * An extension or callback that fails doesn't stop the others. This throws the first failure
   * after they've all run, with later failures attached as suppressed exceptions.
   */
  public suspend fun close()
}
