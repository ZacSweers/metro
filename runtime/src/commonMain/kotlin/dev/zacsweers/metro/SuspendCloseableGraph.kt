// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A dependency graph whose cleanup can suspend.
 *
 * This works like [CloseableGraph], but [close] is a suspend function. Its [OnGraphClose] callbacks
 * can suspend, and it can track graph extensions with either kind of cleanup.
 *
 * This requires Metro's `enableSuspendProviders` option.
 */
@ExperimentalMetroApi
public interface SuspendCloseableGraph {
  /**
   * Closes this graph.
   *
   * The first call closes the graph extensions this graph tracks, then runs its [OnGraphClose]
   * callbacks. Later calls return immediately. If cleanup fails, the graph stays closed and the
   * rest of the cleanup doesn't run. Cancelling the caller doesn't interrupt cleanup.
   */
  public suspend fun close()
}
