// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroApi::class, ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * Runs a suspend graph's close callbacks. Generated code calls this when at least one of them
 * suspends.
 *
 * It marks the coroutine as running close callbacks, and coroutines started inside inherit the
 * mark. [closeGraphChildren] doesn't wait for children under that mark, so a callback that closes
 * an ancestor graph can't deadlock.
 */
public suspend fun runGraphCloseCallbacks(callbacks: suspend () -> Unit) {
  withContext(InCloseCallback) { callbacks() }
}

/**
 * Closes the children that [state] tracks. A graph whose `close()` suspends calls this. Such a
 * graph can track both kinds of closeable graph, so this awaits the children whose `close()`
 * suspends.
 *
 * A child that's already closing somewhere else returns from `close()` right away. This then
 * suspends until that child's cleanup finishes. It doesn't wait from inside a close callback, which
 * [runGraphCloseCallbacks] and [GraphLifecycleState.enterCloseCallback] mark.
 *
 * A child that fails to close doesn't stop the others. Its failure is recorded with [state].
 */
public suspend fun closeGraphChildren(state: GraphLifecycleState) {
  val isInCloseCallback = currentCoroutineContext()[InCloseCallback] != null
  for ((childState, child) in state.takeChildren()) {
    try {
      if (child is SuspendCloseableGraph) {
        child.close()
      } else {
        (child as AutoCloseable).close()
      }
    } catch (failure: Throwable) {
      state.recordCloseFailure(failure)
    }
    if (!isInCloseCallback && childState.shouldAwaitClose()) {
      awaitClose(childState)
    }
  }
}

/**
 * Suspends until [state]'s cleanup finishes. Generated cleanup runs in [runGraphCleanup], so this
 * only gets cancelled when something calls [closeGraphChildren] directly.
 */
private suspend fun awaitClose(state: GraphLifecycleState) {
  suspendCancellableCoroutine { continuation ->
    val isWaiting = state.invokeOnCloseFinished { continuation.resume(Unit) }
    if (!isWaiting) {
      continuation.resume(Unit)
    }
  }
}

/** Marks a coroutine that runs close callbacks. See [runGraphCloseCallbacks]. */
private object InCloseCallback : CoroutineContext.Element, CoroutineContext.Key<InCloseCallback> {
  override val key: CoroutineContext.Key<*>
    get() = this
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
