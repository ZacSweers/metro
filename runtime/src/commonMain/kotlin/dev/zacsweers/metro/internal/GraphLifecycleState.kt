// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Tracks whether a generated closeable graph is closed and which graph extensions it closes with
 * it.
 *
 * The generated `close()` calls [markClosed] first, so only one caller runs cleanup. Extensions
 * register in [addChild] after their init callbacks finish and leave through [removeChild] when
 * they close on their own.
 *
 * Cleanup runs every step even when one fails. Each failure goes to [recordCloseFailure], and
 * [throwCloseFailure] reports them once cleanup finishes.
 */
@OptIn(ExperimentalAtomicApi::class)
public class GraphLifecycleState {
  private val closed = AtomicBoolean(false)

  // Keyed by each child's own state, so a child that closes is removed without a search.
  private val children = GraphChildren()

  // The first failure of this graph's cleanup. Later failures are attached to it as suppressed.
  private val closeFailure = AtomicReference<Throwable?>(null)

  /** Throws if this graph is closed. Extensions call this before they initialize. */
  public fun checkOpen() {
    check(!closed.load()) { "Cannot create an extension of a closed graph." }
  }

  /** Marks this graph closed. Returns false if an earlier call already did. */
  public fun markClosed(): Boolean = closed.compareAndSet(expectedValue = false, newValue = true)

  /**
   * Tracks [child] until it or this graph closes. Throws if this graph is closed.
   *
   * [childState] is the child's own state. A child registers once, after its init callbacks. It can
   * close itself during those callbacks, before it registers here. Its close then had nothing to
   * remove, so registration drops it.
   */
  public fun addChild(child: Any, childState: GraphLifecycleState) {
    checkOpen()
    children.put(childState, child)
    if (childState.closed.load()) {
      children.remove(childState)
    }
    // A close that started after the open check may have already taken the children. If it took
    // this one, it closes it. If it didn't, this graph still can't track it.
    if (closed.load()) {
      children.remove(childState)
      checkOpen()
    }
  }

  /**
   * Tracks [child] like [addChild], for a child whose `close()` doesn't suspend.
   *
   * This graph can close while the child runs its init callbacks. Registration then fails, and
   * nothing else would close the child. Its init callbacks finished, so this closes it before it
   * throws.
   */
  public fun addCloseableChild(child: AutoCloseable, childState: GraphLifecycleState) {
    try {
      addChild(child, childState)
    } catch (rejection: IllegalStateException) {
      try {
        child.close()
      } catch (failure: Throwable) {
        rejection.addSuppressed(failure)
      }
      throw rejection
    }
  }

  /**
   * Stops tracking the child that registered with [childState]. A child calls this on its parent's
   * state when it closes on its own.
   */
  public fun removeChild(childState: GraphLifecycleState) {
    children.remove(childState)
  }

  /** Stops tracking all children and returns them in no particular order. */
  public fun takeChildren(): List<Any> = children.removeAll()

  /**
   * Closes the tracked children. A graph whose `close()` doesn't suspend calls this. Such a graph
   * only tracks children whose `close()` doesn't suspend either.
   *
   * A child that's already closing elsewhere returns from `close()` right away, and this doesn't
   * wait for it. Waiting can deadlock with a close callback that closes or waits on an ancestor.
   *
   * A child that fails to close doesn't stop the others. Its failure goes to [recordCloseFailure].
   */
  public fun closeChildren() {
    for (child in takeChildren()) {
      try {
        (child as AutoCloseable).close()
      } catch (failure: Throwable) {
        recordCloseFailure(failure)
      }
    }
  }

  /**
   * Records the failure of one cleanup step so the remaining steps still run. Generated `close()`
   * functions call this for each child and close callback that throws, then call
   * [throwCloseFailure] once cleanup finishes.
   */
  public fun recordCloseFailure(failure: Throwable) {
    if (closeFailure.compareAndSet(null, failure)) {
      return
    }
    val first = closeFailure.load()
    // Two steps can throw the same exception instance, and an exception can't suppress itself.
    if (first != null && first !== failure) {
      first.addSuppressed(failure)
    }
  }

  /** Throws the first recorded cleanup failure. The later ones are attached to it as suppressed. */
  public fun throwCloseFailure() {
    val failure = closeFailure.exchange(null)
    if (failure != null) {
      throw failure
    }
  }
}
