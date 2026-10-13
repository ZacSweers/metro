// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Tracks whether a generated closeable graph is closed and which graph extensions it must close.
 *
 * Callers must finish creating graph extensions before they close the graph that tracks them.
 */
public class GraphLifecycle(private val graphName: String) {
  private val closed = AtomicBoolean(false)

  // Copy-on-write so tracking a child doesn't need a lock. Graphs usually track few children.
  private val children = AtomicReference<Set<Any>>(emptySet())

  /** Throws if this graph is closed. Graph extensions call this before they initialize. */
  public fun checkOpen() {
    check(!closed.load()) { "Cannot create a graph extension of $graphName because it is closed." }
  }

  /** Marks this graph closed. Returns false if an earlier call already did. */
  public fun markClosed(): Boolean = closed.compareAndSet(false, true)

  /** Tracks [child] until it closes. Throws if this graph is closed. */
  public fun addChild(child: Any) {
    while (true) {
      checkOpen()
      val current = children.load()
      if (children.compareAndSet(current, current + child)) {
        return
      }
    }
  }

  /** Stops tracking [child]. */
  public fun removeChild(child: Any) {
    while (true) {
      val current = children.load()
      if (child !in current) {
        return
      }
      if (children.compareAndSet(current, current - child)) {
        return
      }
    }
  }

  /** Stops tracking all children and returns them. */
  public fun takeChildren(): Set<Any> = children.exchange(emptySet())

  /** Closes the children tracked before [markClosed]. Each child must be [AutoCloseable]. */
  public fun closeChildren() {
    for (child in takeChildren()) {
      (child as AutoCloseable).close()
    }
  }
}
