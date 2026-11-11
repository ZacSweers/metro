// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import java.util.concurrent.ConcurrentHashMap

/**
 * A [ConcurrentHashMap] lets threads register and remove children of the same graph without
 * blocking each other.
 */
internal actual class GraphChildren actual constructor() {
  // Typed as a MutableMap so `keys` compiles to Map.keySet(). ConcurrentHashMap.keySet() returns a
  // type that older Android runtimes don't have.
  private val children: MutableMap<GraphLifecycleState, Any> = ConcurrentHashMap()

  actual fun put(childState: GraphLifecycleState, child: Any) {
    children[childState] = child
  }

  actual fun remove(childState: GraphLifecycleState): Any? = children.remove(childState)

  actual fun removeAll(): List<Pair<GraphLifecycleState, Any>> {
    if (children.isEmpty()) {
      return emptyList()
    }
    val removed = ArrayList<Pair<GraphLifecycleState, Any>>(children.size)
    // Removing key by key hands each child to one caller, even one that removes it during this
    // loop.
    for (childState in children.keys) {
      val child = children.remove(childState)
      if (child != null) {
        removed += childState to child
      }
    }
    return removed
  }
}
