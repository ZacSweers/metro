// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Each generated closeable graph owns one state. The first caller takes responsibility for cleanup.
 */
@OptIn(ExperimentalAtomicApi::class)
public class GraphLifecycleState {
  private val children = AtomicReference<List<Any>?>(emptyList())

  /** Rejects extension creation after this graph has claimed cleanup. */
  public fun requireOpen() {
    check(children.load() != null) { "Cannot create an extension of a closed graph." }
  }

  /** Registers an initialized child before its factory returns it. */
  public fun register(child: Any, childState: GraphLifecycleState) {
    while (true) {
      val current =
        checkNotNull(children.load()) { "Cannot create an extension of a closed graph." }
      if (children.compareAndSet(current, current + child)) {
        // The child can close before its startup callback returns.
        if (childState.children.load() == null) {
          remove(child)
        }
        return
      }
    }
  }

  /** A child can release its registration even if its cleanup failed. */
  public fun remove(child: Any) {
    while (true) {
      val current = children.load() ?: return
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

  /** A null result means another caller already claimed this graph and its children. */
  public fun claimChildren(): List<Any>? = children.exchange(null)

  /** Runs the winning cleanup and releases this graph from its parent. */
  public fun close(graph: Any, parent: GraphLifecycleState?, cleanup: (List<Any>) -> Unit) {
    val owned = claimChildren() ?: return
    try {
      cleanup(owned)
    } finally {
      parent?.remove(graph)
    }
  }

  /** Synchronous graphs can own only children with synchronous cleanup. */
  public fun closeChildren(owned: List<Any>) {
    for (child in owned) {
      (child as AutoCloseable).close()
    }
  }
}
