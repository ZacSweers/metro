// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import org.jetbrains.kotlin.name.ClassId

/**
 * The exclude/replace algebra shared by graph aggregation in the compiler and the IDE.
 *
 * A contribution is removed from a graph when:
 * - its own class (or the `@Origin` class it stands in for) is explicitly excluded, or
 * - another *surviving* contribution declares it (or its origin) in `replaces`.
 *
 * Excludes are applied before replaces are collected, so an excluded contribution never gets to
 * replace anything.
 */
public class MergePlan(
  /** Every contribution id that should be dropped from the aggregation. */
  public val removed: Set<ClassId>,
  /** Excluded classes that matched no present contribution (for diagnostics). */
  public val unmatchedExclusions: Set<ClassId>,
  /** Replaced classes that matched no present contribution (for diagnostics). */
  public val unmatchedReplacements: Set<ClassId>,
)

/**
 * Computes which contributions to remove from an aggregation.
 *
 * @param presentIds every contribution id currently in the aggregation.
 * @param excluded explicitly excluded classes.
 * @param originToIds maps an `@Origin` class to the contribution ids that stand in for it, so
 *   excluding/replacing the origin removes its generated contributions too.
 * @param nestedChildrenOf returns the present ids nested under a given class (compiler-only
 *   `MetroContribution` marker shape); defaults to none.
 * @param ensureActive cancellation callback polled while merging contributions.
 * @param replacesOf the classes a surviving contribution replaces. Only invoked for survivors.
 */
public fun computeMergePlan(
  presentIds: Set<ClassId>,
  excluded: Set<ClassId>,
  originToIds: Map<ClassId, Set<ClassId>> = emptyMap(),
  nestedChildrenOf: (ClassId) -> Set<ClassId> = { emptySet() },
  ensureActive: () -> Unit = {},
  replacesOf: (ClassId) -> Set<ClassId>,
): MergePlan {
  val removed = mutableSetOf<ClassId>()
  val unmatchedExclusions = mutableSetOf<ClassId>()

  for (target in excluded) {
    ensureActive()
    val matched =
      removeTarget(
        target,
        presentIds,
        originToIds,
        nestedChildrenOf,
        removed,
        ensureActive,
      )
    if (!matched) unmatchedExclusions += target
  }

  // Replaces are collected only from survivors, mirroring the compiler: excluded contributions
  // don't get their `replaces` honored, and replacement matching is against the post-exclude set.
  val survivors = mutableSetOf<ClassId>()
  for (presentId in presentIds) {
    ensureActive()
    if (presentId !in removed) survivors += presentId
  }
  val replaced = mutableSetOf<ClassId>()
  for (survivor in survivors) {
    ensureActive()
    for (replacement in replacesOf(survivor)) {
      ensureActive()
      replaced += replacement
    }
  }

  val unmatchedReplacements = mutableSetOf<ClassId>()
  for (target in replaced) {
    ensureActive()
    // Replacements don't expand through the nested-marker shape (matches the compiler).
    val matched =
      removeTarget(
        target,
        survivors,
        originToIds,
        { emptySet() },
        removed,
        ensureActive,
      )
    if (!matched) unmatchedReplacements += target
  }

  return MergePlan(removed, unmatchedExclusions, unmatchedReplacements)
}

private inline fun removeTarget(
  target: ClassId,
  presentIds: Set<ClassId>,
  originToIds: Map<ClassId, Set<ClassId>>,
  nestedChildrenOf: (ClassId) -> Set<ClassId>,
  removed: MutableSet<ClassId>,
  ensureActive: () -> Unit,
): Boolean {
  ensureActive()
  val direct = target in presentIds
  if (direct) removed += target
  val originHits = originToIds[target].orEmpty()
  for (originHit in originHits) {
    ensureActive()
    removed += originHit
  }
  val nested = nestedChildrenOf(target)
  for (nestedId in nested) {
    ensureActive()
    removed += nestedId
  }
  return direct || originHits.isNotEmpty() || nested.isNotEmpty()
}

/**
 * A contribution that participates in [applyExcludesAndReplaces]. [mergeId] is the class whose
 * identity excludes/replaces match against (the contributed/`@Origin` class), or null for
 * contributions that can never be excluded or replaced, like plain injected classes.
 */
public interface MergeContribution {
  public val mergeId: ClassId?
  public val replaces: Set<ClassId>
}

/**
 * Returns [items] with excluded and replaced contributions removed, matching [computeMergePlan]'s
 * excludes-first ordering. A convenience for callers that hold their contributions as a simple list
 * keyed by [MergeContribution.mergeId] (the IDE's binding model), where each item already stands
 * for its own origin so no origin/nested indirection is needed.
 *
 * @param ensureActive cancellation callback polled while filtering contributions.
 */
public fun <T : MergeContribution> applyExcludesAndReplaces(
  items: List<T>,
  excluded: Set<ClassId> = emptySet(),
  ensureActive: () -> Unit = {},
): List<T> {
  if (items.isEmpty()) return items
  ensureActive()
  if (items.size == 1) {
    val item = items[0]
    val mergeId = item.mergeId ?: return items
    if (mergeId in excluded || mergeId in item.replaces) return emptyList()
    return items
  }

  val afterExcludes =
    if (excluded.isEmpty()) {
      items
    } else {
      val filtered = ArrayList<T>(items.size)
      for (item in items) {
        ensureActive()
        if (item.mergeId == null || item.mergeId !in excluded) filtered += item
      }
      filtered
    }
  if (afterExcludes.isEmpty()) {
    return afterExcludes
  }
  val replaced = hashSetOf<ClassId>()
  for (item in afterExcludes) {
    ensureActive()
    for (replacement in item.replaces) {
      ensureActive()
      replaced += replacement
    }
  }
  if (replaced.isEmpty()) return afterExcludes
  // Survivor replaces match against all survivors, including the declaring item itself, keeping
  // this in agreement with computeMergePlan for self-replacing contributions.
  val result = ArrayList<T>(afterExcludes.size)
  for (item in afterExcludes) {
    ensureActive()
    if (item.mergeId == null || item.mergeId !in replaced) result += item
  }
  return result
}

/** Finds lower priority contributions while checking for cancellation. */
public inline fun <BindingType, ConflictKeyType : Any> computeLowerPriorityContributions(
  bindings: List<BindingType>,
  ensureActive: () -> Unit = {},
  conflictKeySelector: (BindingType) -> ConflictKeyType,
  prioritySelector: (BindingType) -> Int,
): Set<BindingType> {
  if (bindings.size < 2) return emptySet()
  var hasExplicitPriority = false
  for (binding in bindings) {
    ensureActive()
    if (prioritySelector(binding) != Int.MIN_VALUE) {
      hasExplicitPriority = true
      break
    }
  }
  if (!hasExplicitPriority) return emptySet()

  val highestPrioritiesByKey = HashMap<ConflictKeyType, Int>(bindings.size)
  for (binding in bindings) {
    ensureActive()
    val conflictKey = conflictKeySelector(binding)
    val priority = prioritySelector(binding)
    val highestPriority = highestPrioritiesByKey[conflictKey]
    if (highestPriority == null || priority > highestPriority) {
      highestPrioritiesByKey[conflictKey] = priority
    }
  }

  val result = mutableSetOf<BindingType>()
  for (binding in bindings) {
    ensureActive()
    val priority = prioritySelector(binding)
    val highestPriority = highestPrioritiesByKey.getValue(conflictKeySelector(binding))
    if (priority < highestPriority) result += binding
  }
  return result
}
