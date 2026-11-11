// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/** Web targets run on one thread, so a plain [HashMap] is enough. */
internal actual class GraphChildren actual constructor() {
  private val children = HashMap<GraphLifecycleState, Any>()

  actual fun put(childState: GraphLifecycleState, child: Any) {
    children[childState] = child
  }

  actual fun remove(childState: GraphLifecycleState): Any? = children.remove(childState)

  actual fun removeAll(): List<Any> {
    val removed = children.values.toList()
    children.clear()
    return removed
  }
}
