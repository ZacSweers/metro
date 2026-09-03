// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import junit.framework.TestCase

/** Timing policy tests use a monotonic fake clock and never wait on real timers. */
class AutomaticRefreshWindowTest : TestCase() {
  fun testColdRequestIsReadyImmediately() {
    assertEquals(0L, AutomaticRefreshWindow(nowMillis = { 0 }).remainingMillis())
  }

  fun testEditsExtendTheIdleWindow() {
    var now = 0L
    val window = AutomaticRefreshWindow(nowMillis = { now })
    window.changed()
    now = 1_000
    assertEquals(1_000L, window.remainingMillis())
    window.changed()
    now = 2_000
    assertEquals(1_000L, window.remainingMillis())
    now = 3_000
    assertEquals(0L, window.remainingMillis())
  }

  fun testRetriesWaitForBothTheIntervalAndIdleWindow() {
    var now = 0L
    val window = AutomaticRefreshWindow(nowMillis = { now })
    window.attemptStarted()
    now = 1_000
    window.changed()
    now = 3_000
    assertEquals(7_000L, window.remainingMillis())
    now = 9_000
    window.changed()
    now = 10_000
    assertEquals(1_000L, window.remainingMillis())
    now = 11_000
    assertEquals(0L, window.remainingMillis())
  }
}
