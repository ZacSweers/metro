// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * The children that a [GraphLifecycleState] tracks, keyed by each child's own state. States compare
 * by identity, so two graphs that are equal are still tracked separately.
 *
 * Each function is atomic on its own. [GraphLifecycleState] orders its calls so that a child that
 * registers while its parent closes is handed to exactly one of them.
 *
 * Each platform uses the cheapest map that's safe for its threads.
 */
internal expect class GraphChildren() {
  /** Tracks [child] under [childState]. */
  fun put(childState: GraphLifecycleState, child: Any)

  /** Stops tracking the child registered under [childState] and returns it, or null. */
  fun remove(childState: GraphLifecycleState): Any?

  /** Stops tracking every child and returns them with their states in no particular order. */
  fun removeAll(): List<Pair<GraphLifecycleState, Any>>
}
