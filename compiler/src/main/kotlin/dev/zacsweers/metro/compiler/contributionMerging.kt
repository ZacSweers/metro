// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.name.ClassId

/**
 * Computes the set of class IDs that have been "outranked" by higher-ranked bindings.
 *
 * This groups bindings by their type key, finds groups with multiple bindings, and for each group
 * keeps only the highest-ranked bindings. Returns the class IDs of all outranked bindings.
 *
 * @param BindingType the binding type
 * @param TypeKeyType the type key type (must support equality for grouping)
 * @param bindings the list of bindings to process
 * @param typeKeySelector extracts the type key from a binding (used for grouping)
 * @param rankSelector extracts the rank from a binding (higher rank wins)
 * @param classId extracts the class ID from a binding (for the result set)
 * @return the set of class IDs that were outranked
 */
internal inline fun <BindingType, TypeKeyType> computeOutrankedBindings(
  bindings: List<BindingType>,
  typeKeySelector: (BindingType) -> TypeKeyType,
  rankSelector: (BindingType) -> Long,
  classId: (BindingType) -> ClassId,
): Set<ClassId> {
  if (bindings.isEmpty()) return emptySet()

  val result = HashSet<ClassId>(bindings.size)

  val bindingsByTypeKey = bindings.groupBy(typeKeySelector).filter { (_, group) -> group.size > 1 }

  for ((_, bindingGroup) in bindingsByTypeKey) {
    val bindingsByRank = bindingGroup.groupBy(rankSelector)

    val maxKey =
      bindingsByRank.keys.maxOrNull()
        // Map was empty, nothing to do here
        ?: continue

    val topBindings = bindingsByRank.getValue(maxKey)

    // These are the bindings that were outranked and should not be processed further
    for (binding in (bindingGroup - topBindings.toSet())) {
      result += classId(binding)
    }
  }

  return result
}
