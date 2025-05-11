/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.zacsweers.metro.compiler.graph

/**
 * Returns a new list where each element is preceded by its results in [sourceToTarget]. The first
 * element will return no values in [sourceToTarget].
 *
 * Implementation inspiration: https://www.interviewcake.com/concept/java/topological-sort
 *
 * Modifications from Zipline
 * - Add [onMissing] check
 * - Add [errorHandler] for customizing how errors are handled
 *
 * @param sourceToTarget a function that returns nodes that should precede the argument in the
 *   result.
 * @see <a href="Adapted from
 *   https://github.com/cashapp/zipline/blob/30ca7c9d782758737e9d20e8d9505930178d1992/zipline/src/hostMain/kotlin/app/cash/zipline/internal/topologicalSort.kt">Adapted
 *   from Zipline's implementation</a>
 */
internal fun <T> Iterable<T>.topologicalSort(
  sourceToTarget: (T) -> Iterable<T>,
  errorHandler: TopoErrorHandler<T> = SimpleErrorHandler(),
  onMissing: (source: T, target: T) -> Unit = { source, target ->
    throw IllegalArgumentException("No element for $target found for $source")
  },
): List<T> {
  // Require a set so the onMissing checks are O(1)
  val sourceSet = toSet()
  // Build a reverse index, from targets to sources.
  val targetToSources = mutableMapOf<T, MutableSet<T>>()
  val queue = ArrayDeque<T>()
  for (source in sourceSet) {
    var hasTargets = false
    for (target in sourceToTarget(source)) {
      // missing vertex
      if (target !in sourceSet) {
        onMissing(source, target)
        continue
      }
      val set = targetToSources.getOrPut(target, ::mutableSetOf)
      set += source
      hasTargets = true
    }

    // No targets means all this source's targets are satisfied, queue it up.
    if (!hasTargets) {
      queue += source
    }
  }

  // Set for O(1) lookups during satisfied checks
  val result = LinkedHashSet<T>(capacity(sourceSet.size))
  while (queue.isNotEmpty()) {
    val node = queue.removeFirst()
    result += node

    val potentiallySatisfied = targetToSources[node].orEmpty()
    for (source in potentiallySatisfied) {
      // If all of a source's *relevant* targets are satisfied, queue it up.
      if (
        source !in queue &&
        sourceToTarget(source).all { t ->
          t !in sourceSet || // ignore edges we dropped earlier, i.e. onMissing()
            t in result   ||
            t in queue
        }
      ) {
        queue += source
      }
    }
  }

  if (result.size != sourceSet.size) {
    val unordered = sourceSet - result.toSet()
    errorHandler.onError(unordered, targetToSources, sourceToTarget, result)
  }
  return result.toList()
}

private fun capacity(expectedSize: Int): Int =
  if (expectedSize < 3) 3 else expectedSize + expectedSize / 3 + 1

internal fun interface TopoErrorHandler<T> {
  fun onError(
    unorderedItems: Set<T>,
    targetToSources: Map<T, Set<T>>,
    sourceToTarget: (T) -> Iterable<T>,
    result: Iterable<T>,
  )
}

internal class SimpleErrorHandler<T>(
  private val onError: (message: String) -> Nothing = { throw IllegalArgumentException(it) }
) : TopoErrorHandler<T> {
  override fun onError(
    unorderedItems: Set<T>,
    targetToSources: Map<T, Set<T>>,
    sourceToTarget: (T) -> Iterable<T>,
    result: Iterable<T>,
  ): Nothing {
    val message = buildString {
      append("No topological ordering is possible for these items:")

      for (unorderedItem in unorderedItems) {
        append("\n  ")
        append(unorderedItem)
        val unsatisfiedDeps = sourceToTarget(unorderedItem).toSet() - result.toSet()
        unsatisfiedDeps.joinTo(this, separator = ", ", prefix = " (", postfix = ")")
      }
    }
    onError(message)
  }
}

internal fun <T> List<T>.isTopologicallySorted(sourceToTarget: (T) -> Iterable<T>): Boolean {
  val seenNodes = mutableSetOf<T>()
  for (node in this) {
    if (sourceToTarget(node).any { it !in seenNodes }) return false
    seenNodes.add(node)
  }
  return true
}
