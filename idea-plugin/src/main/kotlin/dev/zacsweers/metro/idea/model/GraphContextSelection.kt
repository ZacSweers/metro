// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

/** Whether this path is [candidate] itself or one of its concrete extension descendants. */
internal fun GraphPath.isAtOrBelow(candidate: GraphPath): Boolean {
  if (dynamicGraphId != candidate.dynamicGraphId) return false
  if (segments.size < candidate.segments.size) return false
  return segments.takeLast(candidate.segments.size) == candidate.segments
}

/** The closest context represented by [pinnedPath], including an inherited parent context. */
internal fun Iterable<GraphContext>.matchingContext(pinnedPath: GraphPath): GraphContext? {
  return filter { pinnedPath.isAtOrBelow(it.path) }.maxByOrNull { it.path.segments.size }
}

/** The closest context entry represented by [pinnedPath], including an inherited parent context. */
internal fun <T> Map<GraphContext, T>.matchingContextEntry(
  pinnedPath: GraphPath
): Map.Entry<GraphContext, T>? {
  return entries
    .asSequence()
    .filter { (context) -> pinnedPath.isAtOrBelow(context.path) }
    .maxByOrNull { (context) -> context.path.segments.size }
}
