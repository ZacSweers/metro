// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

/** Whether this path is [candidate] itself or one of its concrete extension descendants. */
internal fun GraphPath.isAtOrBelow(candidate: GraphPath): Boolean {
  if (dynamicGraphId != candidate.dynamicGraphId) return false
  if (segments.size < candidate.segments.size) return false
  return segments.takeLast(candidate.segments.size) == candidate.segments
}

/** The closest inherited context, or the single child context reachable from [pinnedPath]. */
internal fun Iterable<GraphContext>.matchingContext(pinnedPath: GraphPath): GraphContext? {
  val contexts = toList()
  contexts
    .filter { pinnedPath.isAtOrBelow(it.path) }
    .maxByOrNull { it.path.segments.size }
    ?.let {
      return it
    }
  return contexts.singleOrNull { it.path.isAtOrBelow(pinnedPath) }
}

/** The closest inherited entry, or the single child entry reachable from [pinnedPath]. */
internal fun <T> Map<GraphContext, T>.matchingContextEntry(
  pinnedPath: GraphPath
): Map.Entry<GraphContext, T>? {
  entries
    .asSequence()
    .filter { (context) -> pinnedPath.isAtOrBelow(context.path) }
    .maxByOrNull { (context) -> context.path.segments.size }
    ?.let {
      return it
    }
  return entries.singleOrNull { (context) -> context.path.isAtOrBelow(pinnedPath) }
}
