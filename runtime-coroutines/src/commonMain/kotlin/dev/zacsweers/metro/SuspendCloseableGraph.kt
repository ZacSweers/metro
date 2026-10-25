// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A [CloseableGraph] alternative whose generated [close] can suspend, so its `@OnGraphClose`
 * callbacks can too. Requires Metro's suspend provider support.
 */
@ExperimentalMetroApi
public interface SuspendCloseableGraph {
  /**
   * Closes the graph's extensions and runs its close callbacks. Only the first call does anything.
   * Cancelling the caller doesn't stop cleanup partway through.
   */
  public suspend fun close()
}
