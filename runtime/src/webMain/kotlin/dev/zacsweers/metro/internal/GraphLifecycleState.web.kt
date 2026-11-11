// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

private var closeCallbackDepthOfThread = 0

internal actual fun closeCallbackDepth(): Int = closeCallbackDepthOfThread

internal actual fun setCloseCallbackDepth(depth: Int) {
  closeCallbackDepthOfThread = depth
}

/**
 * Returns right away. A `close()` that doesn't suspend runs to the end before anything else runs on
 * this one thread. So a child is only mid-close here through re-entry from one of its close
 * callbacks, and [GraphLifecycleState.shouldAwaitClose] skips waiting then.
 */
internal actual fun awaitCloseFinished(state: GraphLifecycleState) {}
