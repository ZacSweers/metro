// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

internal class BindingGraphErrorHandler<T>(
  private val onMissing: (source: T, target: T) -> Unit,
  private val onCycle: (List<T>) -> Nothing,
) : TopoErrorHandler<T> {
  override fun onError(
    unorderedItems: Set<T>,
    targetToSources: Map<T, Set<T>>,
    sourceToTarget: (T) -> Iterable<T>,
    result: Iterable<T>,
  ) {
    val cycleStart =
      unorderedItems.firstOrNull { u ->
        // every node waits on another unordered node
        sourceToTarget(u).any { it in unorderedItems }
      }
    val isCycle = cycleStart != null

    if (!isCycle) {
      val resultSet = result.toSet()
      val (culprit, missingDep: T) =
        unorderedItems.firstNotNullOf { u ->
          val missingDep = sourceToTarget(u).find { it !in resultSet }
          missingDep?.let { u to it }
        }
      onMissing(culprit, missingDep)
    } else {
      // We have a cycle, walk forward until we return to the start
      val indicesByEntry = LinkedHashMap<T, Int>() // preserve insertion order

      var next: T = cycleStart
      while (next !in indicesByEntry) {
        indicesByEntry[next] = indicesByEntry.size
        // guaranteed at this point to have a dep in the unordered set,
        // find and follow it
        next = sourceToTarget(next).first { it in unorderedItems }
      }

      val cycleStartIndex = indicesByEntry.getValue(next)
      val cycle = indicesByEntry.keys.drop(cycleStartIndex) + next

      onCycle(cycle)
    }
  }
}
