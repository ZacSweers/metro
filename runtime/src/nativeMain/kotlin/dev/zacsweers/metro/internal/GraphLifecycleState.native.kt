// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal private var closeCallbackDepthOfThread = 0

internal actual fun closeCallbackDepth(): Int = closeCallbackDepthOfThread

internal actual fun setCloseCallbackDepth(depth: Int) {
  closeCallbackDepthOfThread = depth
}

/**
 * Sleeps on the process-wide parker that [DoubleCheckInitGuard] uses. This wait can last as long as
 * user cleanup, so it holds no guard and doesn't count toward the guards' waiters. Guard releases
 * don't broadcast for it. The state's cleanup broadcasts once when it finishes, and wakeups from
 * guards just make this recheck.
 */
internal actual fun awaitCloseFinished(state: GraphLifecycleState) {
  val isWaiting = state.invokeOnCloseFinished {
    parkerLock()
    parkerBroadcast()
    parkerUnlock()
  }
  if (!isWaiting) {
    return
  }
  parkerLock()
  try {
    // The broadcast needs the parker mutex, so it can't slip in between this check and the wait.
    while (!state.isCloseFinished()) {
      parkerWait(0L)
    }
  } finally {
    parkerUnlock()
  }
}
