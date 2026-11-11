// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroApi::class, ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs a suspend graph's cleanup to completion even when the caller is cancelled.
 *
 * Generated code calls this instead of `withContext(NonCancellable)` because this artifact doesn't
 * expose kotlinx-coroutines to the modules that use it. An inline function would compile that same
 * `withContext` call into each graph, so this one isn't inline.
 */
public suspend fun runGraphCleanup(cleanup: suspend () -> Unit) {
  withContext(NonCancellable) { cleanup() }
}

/**
 * Closes the children that [state] tracks. A graph whose `close()` suspends calls this. Such a
 * graph can track both kinds of closeable graph, so this awaits the children whose `close()`
 * suspends.
 *
 * A child that's already closing elsewhere returns from `close()` right away, and this doesn't wait
 * for it. Waiting can deadlock with a close callback that closes or waits on an ancestor.
 *
 * A child that fails to close doesn't stop the others. Its failure is recorded with [state].
 */
public suspend fun closeGraphChildren(state: GraphLifecycleState) {
  for (child in state.takeChildren()) {
    try {
      if (child is SuspendCloseableGraph) {
        child.close()
      } else {
        (child as AutoCloseable).close()
      }
    } catch (failure: Throwable) {
      state.recordCloseFailure(failure)
    }
  }
}

/**
 * Tracks [child] with [parent] like [GraphLifecycleState.addCloseableChild], for a child whose
 * `close()` suspends.
 *
 * Generated code calls this from the suspend function that runs the child's init callbacks. A child
 * that registers from its constructor has nowhere to await its `close()`, so it calls
 * [GraphLifecycleState.addChild] and stays open if [parent] is already closed.
 */
public suspend fun addSuspendCloseableChild(
  parent: GraphLifecycleState,
  child: SuspendCloseableGraph,
  childState: GraphLifecycleState,
) {
  try {
    parent.addChild(child, childState)
  } catch (rejection: IllegalStateException) {
    try {
      child.close()
    } catch (failure: Throwable) {
      rejection.addSuppressed(failure)
    }
    throw rejection
  }
}
