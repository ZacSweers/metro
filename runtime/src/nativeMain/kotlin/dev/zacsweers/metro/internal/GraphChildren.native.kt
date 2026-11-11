// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * A [HashMap] that only one thread touches at a time. Each function holds the
 * [DoubleCheckInitGuard] it extends for a single map operation and never calls out while it does.
 */
internal actual class GraphChildren actual constructor() : DoubleCheckInitGuard() {
  private val children = HashMap<GraphLifecycleState, Any>()

  actual fun put(childState: GraphLifecycleState, child: Any) {
    guarded { children[childState] = child }
  }

  actual fun remove(childState: GraphLifecycleState): Any? = guarded { children.remove(childState) }

  actual fun removeAll(): List<Pair<GraphLifecycleState, Any>> = guarded {
    val removed = children.map { (childState, child) -> childState to child }
    children.clear()
    removed
  }
}
