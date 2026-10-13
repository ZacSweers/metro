// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import junit.framework.TestCase

class DaemonRestartStateTest : TestCase() {
  fun testIdleRequestIsConsumedOnce() {
    val state = DaemonRestartState<Any>()

    state.requestRestart()

    assertTrue(state.takePendingRestart())
    assertFalse(state.takePendingRestart())
  }

  fun testActiveDaemonKeepsRequestPendingUntilItStops() {
    val owner = Any()
    val state = DaemonRestartState<Any>()
    state.daemonStarted(listOf(owner))

    state.requestRestart()

    assertFalse(state.takePendingRestart())
    assertTrue(state.hasPendingRestart)
    state.daemonStopped(listOf(owner))
    assertTrue(state.takePendingRestart())
  }

  fun testOverlappingDaemonPassesKeepRequestPendingUntilAllStop() {
    val firstOwner = Any()
    val secondOwner = Any()
    val state = DaemonRestartState<Any>()
    state.daemonStarted(listOf(firstOwner))
    state.daemonStarted(listOf(secondOwner))
    state.requestRestart()

    state.daemonStopped(listOf(firstOwner))

    assertFalse(state.takePendingRestart())
    assertTrue(state.hasPendingRestart)
    state.daemonStopped(listOf(secondOwner))
    assertTrue(state.takePendingRestart())
  }

  fun testRepeatedStopForTheSamePassIsIdempotent() {
    val firstOwner = Any()
    val secondOwner = Any()
    val state = DaemonRestartState<Any>()
    state.daemonStarted(listOf(firstOwner, secondOwner))
    state.requestRestart()

    state.daemonStopped(listOf(firstOwner))
    state.daemonStopped(listOf(firstOwner))

    assertFalse(state.takePendingRestart())
    state.daemonStopped(listOf(secondOwner))
    assertTrue(state.takePendingRestart())
  }

  fun testRepeatedRequestsAreCoalesced() {
    val state = DaemonRestartState<Any>()

    repeat(3) { state.requestRestart() }

    assertTrue(state.takePendingRestart())
    assertFalse(state.takePendingRestart())
  }

  fun testClearDropsPendingRequest() {
    val state = DaemonRestartState<Any>()
    state.requestRestart()

    state.clear()

    assertFalse(state.hasPendingRestart)
    assertFalse(state.takePendingRestart())
  }
}
