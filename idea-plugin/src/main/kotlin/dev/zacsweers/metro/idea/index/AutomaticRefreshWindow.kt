// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

/**
 * Groups edits behind an idle delay and spaces automatic build attempts apart. Explicit requests
 * bypass this window. The clock is monotonic; callbacks and the coordinator may use it
 * concurrently.
 */
internal class AutomaticRefreshWindow(
  private val idleMillis: Long = 2_000,
  private val intervalMillis: Long = 10_000,
  private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  private var lastChange: Long? = null
  private var lastAttempt: Long? = null

  @Synchronized
  fun changed() {
    lastChange = nowMillis()
  }

  @Synchronized
  fun attemptStarted() {
    lastAttempt = nowMillis()
  }

  /** Remaining delay is recomputed when a timer wakes, including edits received during the wait. */
  @Synchronized
  fun remainingMillis(): Long {
    val now = nowMillis()
    val idleRemaining = lastChange?.let { it + idleMillis - now } ?: 0
    val intervalRemaining = lastAttempt?.let { it + intervalMillis - now } ?: 0
    return maxOf(0, idleRemaining, intervalRemaining)
  }
}
