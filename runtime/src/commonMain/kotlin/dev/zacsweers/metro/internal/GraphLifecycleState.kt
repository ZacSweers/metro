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
 * register in [addChild] after their init callbacks finish and call [leaveParent] when they close
 * on their own.
 *
 * Cleanup runs every step even when one fails. Each failure goes to [recordCloseFailure], and
 * [throwCloseFailure] reports them once cleanup finishes.
 */
@OptIn(ExperimentalAtomicApi::class)
public class GraphLifecycleState {
  private val closed = AtomicBoolean(false)

  // The graph that owns this state, set while its parent tracks it. Clearing it is how a child
  // stops being tracked, so that never has to search the parent's chain.
  private val trackedGraph = AtomicReference<Any?>(null)

  // The states of this graph's children, newest first. Links are immutable, so registration is one
  // compare-and-set and doesn't need a lock.
  private val children = AtomicReference<ChildLink?>(null)

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
   * stop, so registration drops it.
   */
  public fun addChild(child: Any, childState: GraphLifecycleState) {
    childState.trackedGraph.store(child)
    while (!closed.load()) {
      val head = children.load()
      if (children.compareAndSet(head, linkChild(head, childState))) {
        break
      }
    }
    if (childState.closed.load()) {
      childState.leaveParent()
    }
    // A close that started after the last open check may have already taken the children.
    if (closed.load()) {
      childState.leaveParent()
      checkOpen()
    }
  }

  /**
   * Returns [head] with a link to [childState] in front.
   *
   * A child that leaves only clears its [trackedGraph], so its link stays in the chain. This drops
   * those links each time the chain has doubled since it last did. The chain stays proportional to
   * the number of tracked children, and the copying averages out to a constant cost per child.
   */
  private fun linkChild(head: ChildLink?, childState: GraphLifecycleState): ChildLink {
    if (head == null) {
      return ChildLink(childState, next = null, size = 1, prunedSize = 0)
    }
    val hasDoubled = head.size >= 2 * head.prunedSize + MIN_PRUNING_SIZE
    if (!hasDoubled) {
      return ChildLink(childState, head, head.size + 1, head.prunedSize)
    }
    val tracked = ArrayList<GraphLifecycleState>()
    var link: ChildLink? = head
    while (link != null) {
      if (link.state.trackedGraph.load() != null) {
        tracked += link.state
      }
      link = link.next
    }
    // Rebuild from the oldest child so the chain stays newest first.
    var rest: ChildLink? = null
    for (index in tracked.indices.reversed()) {
      rest = ChildLink(tracked[index], rest, size = tracked.size - index, prunedSize = tracked.size)
    }
    return ChildLink(childState, rest, size = tracked.size + 1, prunedSize = tracked.size)
  }

  /**
   * Stops this graph's parent from tracking it. A graph calls this on its own state when it closes.
   */
  public fun leaveParent() {
    trackedGraph.store(null)
  }

  /** Stops tracking all children and returns them, most recently added first. */
  public fun takeChildren(): List<Any> {
    var link = children.exchange(null)
    if (link == null) {
      return emptyList()
    }
    val taken = ArrayList<Any>(link.size)
    while (link != null) {
      val graph = link.state.trackedGraph.exchange(null)
      if (graph != null) {
        taken += graph
      }
      link = link.next
    }
    return taken
  }

  /**
   * Closes the tracked children. A graph whose `close()` doesn't suspend calls this. Such a graph
   * only tracks children whose `close()` doesn't suspend either.
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

/**
 * One entry in a graph's chain of children. [size] counts the links from this one to the end of the
 * chain. [prunedSize] is the chain's size right after the last time [linkChild] pruned it.
 */
private class ChildLink(
  val state: GraphLifecycleState,
  val next: ChildLink?,
  val size: Int,
  val prunedSize: Int,
)

// A chain this short isn't worth pruning.
private const val MIN_PRUNING_SIZE = 8
