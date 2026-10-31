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
 * register in [addChild] after their init callbacks finish and leave in [removeChild] when they
 * close on their own.
 */
@OptIn(ExperimentalAtomicApi::class)
public class GraphLifecycleState {
  private val closed = AtomicBoolean(false)

  // Copy-on-write so registration doesn't need a lock. Graphs usually own few extensions.
  private val children = AtomicReference<List<Any>>(emptyList())

  /** Throws if this graph is closed. Extensions call this before they initialize. */
  public fun checkOpen() {
    check(!closed.load()) { "Cannot create an extension of a closed graph." }
  }

  /** Marks this graph closed. Returns false if an earlier call already did. */
  public fun markClosed(): Boolean = closed.compareAndSet(expectedValue = false, newValue = true)

  /**
   * Tracks [child] until it or this graph closes. Throws if this graph is closed.
   *
   * [childState] is the child's own state. A child can close itself during its init callbacks,
   * before it registers here. Its close then had nothing to remove, so registration drops it.
   */
  public fun addChild(child: Any, childState: GraphLifecycleState) {
    while (true) {
      checkOpen()
      val current = children.load()
      if (current.any { it === child }) {
        return
      }
      if (children.compareAndSet(current, current + child)) {
        break
      }
    }
    if (childState.closed.load()) {
      removeChild(child)
    }
    // A close that started after the open check may have already taken the children.
    if (closed.load()) {
      removeChild(child)
      checkOpen()
    }
  }

  /** Stops tracking [child]. Children are compared by identity. */
  public fun removeChild(child: Any) {
    while (true) {
      val current = children.load()
      val index = current.indexOfFirst { it === child }
      if (index < 0) {
        return
      }
      val remaining = current.toMutableList().apply { removeAt(index) }
      if (children.compareAndSet(current, remaining)) {
        return
      }
    }
  }

  /** Stops tracking all children and returns them in registration order. */
  public fun takeChildren(): List<Any> = children.exchange(emptyList())
}
