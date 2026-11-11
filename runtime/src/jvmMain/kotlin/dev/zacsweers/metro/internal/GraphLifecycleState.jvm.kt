// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import java.util.concurrent.CountDownLatch

// Unset outside close callbacks, so threads that leave them keep no value.
private val closeCallbackDepths = ThreadLocal<Int>()

internal actual fun closeCallbackDepth(): Int = closeCallbackDepths.get() ?: 0

internal actual fun setCloseCallbackDepth(depth: Int) {
  if (depth == 0) {
    closeCallbackDepths.remove()
  } else {
    closeCallbackDepths.set(depth)
  }
}

/**
 * Waits on a latch that the state's cleanup releases. Interrupts don't cut the wait short, because
 * the parent's callbacks must not run before the child's cleanup finishes. The interrupt is
 * restored afterward.
 */
internal actual fun awaitCloseFinished(state: GraphLifecycleState) {
  val latch = CountDownLatch(1)
  if (!state.invokeOnCloseFinished(latch::countDown)) {
    return
  }
  var interrupted = false
  while (true) {
    try {
      latch.await()
      break
    } catch (_: InterruptedException) {
      interrupted = true
    }
  }
  if (interrupted) {
    Thread.currentThread().interrupt()
  }
}
