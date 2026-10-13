// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A dependency graph whose lifecycle Metro manages.
 *
 * Implement this on a [DependencyGraph] or [GraphExtension] to use [OnGraphInit] and [OnGraphClose]
 * callbacks. Metro generates [close], so don't implement it yourself.
 *
 * ```kotlin
 * @DependencyGraph(AppScope::class)
 * interface AppGraph : CloseableGraph {
 *   @OnGraphInit
 *   fun initialize(analytics: Analytics) {
 *     analytics.start()
 *   }
 *
 *   @OnGraphClose
 *   fun shutdown(analytics: Analytics) {
 *     analytics.flush()
 *   }
 * }
 * ```
 *
 * Use [SuspendCloseableGraph] instead when cleanup needs to suspend.
 */
@ExperimentalMetroApi
public interface CloseableGraph : AutoCloseable {
  /**
   * Closes this graph.
   *
   * The first call closes the graph extensions this graph tracks, then runs its [OnGraphClose]
   * callbacks. Later calls return immediately. If cleanup fails, the graph stays closed and the
   * rest of the cleanup doesn't run.
   */
  override fun close()
}
