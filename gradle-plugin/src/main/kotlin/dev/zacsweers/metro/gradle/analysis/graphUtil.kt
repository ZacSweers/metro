// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph

/** Kahn's algorithm for topological sorting. */
internal fun <N : Any> Graph<N>.topologicalSort(): List<N> {
  check(isDirected)
  if (edges().isEmpty()) return emptyList()

  val inDegrees = nodes().associateWithTo(mutableMapOf()) { inDegree(it) }
  val queue = ArrayDeque(inDegrees.filterValues { it == 0 }.keys)
  val result = mutableListOf<N>()

  while (queue.isNotEmpty()) {
    val v = queue.removeFirst()
    result.add(v)
    for (w in successors(v)) {
      val newDegree = inDegrees[w]!! - 1
      inDegrees[w] = newDegree
      if (newDegree == 0) queue.add(w)
    }
  }

  check(result.size == nodes().size) { "Graph contains a cycle" }
  return result
}

internal fun <V : Any> Graph<V>.computeRoots(vs: Set<V> = nodes()) =
  vs.filterTo(mutableSetOf()) { inDegree(it) == 0 }.ifEmpty { vs }
