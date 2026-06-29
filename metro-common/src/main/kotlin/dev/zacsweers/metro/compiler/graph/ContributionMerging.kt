// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.flatMapToSet
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
 * @param replacesOf the classes a surviving contribution replaces. Only invoked for survivors.
 */
public fun computeMergePlan(
  presentIds: Set<ClassId>,
  excluded: Set<ClassId>,
  originToIds: Map<ClassId, Set<ClassId>> = emptyMap(),
  nestedChildrenOf: (ClassId) -> Set<ClassId> = { emptySet() },
  replacesOf: (ClassId) -> Set<ClassId>,
): MergePlan {
  val removed = mutableSetOf<ClassId>()
  val unmatchedExclusions = mutableSetOf<ClassId>()

  for (target in excluded) {
    val matched = removeTarget(target, presentIds, originToIds, nestedChildrenOf, removed)
    if (!matched) unmatchedExclusions += target
  }

  // Replaces are collected only from survivors, mirroring the compiler: excluded contributions
  // don't get their `replaces` honored, and replacement matching is against the post-exclude set.
  val survivors = presentIds - removed
  val replaced = survivors.flatMapToSet { replacesOf(it) }

  val unmatchedReplacements = mutableSetOf<ClassId>()
  for (target in replaced) {
    // Replacements don't expand through the nested-marker shape (matches the compiler).
    val matched = removeTarget(target, survivors, originToIds, { emptySet() }, removed)
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
): Boolean {
  val direct = target in presentIds
  if (direct) removed += target
  val originHits = originToIds[target].orEmpty()
  removed += originHits
  val nested = nestedChildrenOf(target)
  removed += nested
  return direct || originHits.isNotEmpty() || nested.isNotEmpty()
}

/**
 * A contribution that participates in [applyExcludesAndReplaces]. [mergeId] is the class whose
 * identity excludes/replaces match against (the contributed/`@Origin` class), or null for
 * contributions that can never be excluded or replaced (e.g. plain injected classes).
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
 */
public fun <T : MergeContribution> applyExcludesAndReplaces(
  items: List<T>,
  excluded: Set<ClassId> = emptySet(),
): List<T> {
  val afterExcludes =
    if (excluded.isEmpty()) items
    else items.filter { it.mergeId == null || it.mergeId !in excluded }
  if (afterExcludes.size < 2) return afterExcludes
  val replaced = afterExcludes.flatMapTo(hashSetOf()) { it.replaces }
  if (replaced.isEmpty()) return afterExcludes
  return afterExcludes.filter { it.mergeId == null || it.mergeId !in replaced }
}
