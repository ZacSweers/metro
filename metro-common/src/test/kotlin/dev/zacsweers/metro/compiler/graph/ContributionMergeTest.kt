// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.name.ClassId
import org.junit.Test

class ContributionMergeTest {

  private fun id(name: String): ClassId = ClassId.fromString("test/$name")

  @Test
  fun `excludes remove by id`() {
    val plan =
      computeMergePlan(
        presentIds = setOf(id("A"), id("B")),
        excluded = setOf(id("A")),
        replacesOf = { emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("A"))
    assertThat(plan.unmatchedExclusions).isEmpty()
  }

  @Test
  fun `excludes remove origin-backed contributions`() {
    val plan =
      computeMergePlan(
        presentIds = setOf(id("AProvider"), id("B")),
        excluded = setOf(id("A")),
        originToIds = mapOf(id("A") to setOf(id("AProvider"))),
        replacesOf = { emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("AProvider"))
    assertThat(plan.unmatchedExclusions).isEmpty()
  }

  @Test
  fun `unmatched exclusions are reported`() {
    val plan =
      computeMergePlan(
        presentIds = setOf(id("A")),
        excluded = setOf(id("Missing")),
        replacesOf = { emptySet() },
      )
    assertThat(plan.removed).isEmpty()
    assertThat(plan.unmatchedExclusions).containsExactly(id("Missing"))
  }

  @Test
  fun `replaces from survivors remove the replaced contribution`() {
    val plan =
      computeMergePlan(
        presentIds = setOf(id("Real"), id("Fake")),
        excluded = emptySet(),
        replacesOf = { if (it == id("Real")) setOf(id("Fake")) else emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("Fake"))
    assertThat(plan.unmatchedReplacements).isEmpty()
  }

  @Test
  fun `excluded contributions do not get their replaces honored`() {
    // Real replaces Fake, but Real is excluded, so Fake survives.
    val plan =
      computeMergePlan(
        presentIds = setOf(id("Real"), id("Fake")),
        excluded = setOf(id("Real")),
        replacesOf = { if (it == id("Real")) setOf(id("Fake")) else emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("Real"))
  }

  @Test
  fun `replaces expand through origin map`() {
    val plan =
      computeMergePlan(
        presentIds = setOf(id("Real"), id("FakeProvider")),
        excluded = emptySet(),
        originToIds = mapOf(id("Fake") to setOf(id("FakeProvider"))),
        replacesOf = { if (it == id("Real")) setOf(id("Fake")) else emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("FakeProvider"))
  }

  @Test
  fun `nested children are removed on exclude`() {
    val plan =
      computeMergePlan(
        presentIds = setOf(id("Parent"), id("Parent.Nested")),
        excluded = setOf(id("Parent")),
        nestedChildrenOf = { if (it == id("Parent")) setOf(id("Parent.Nested")) else emptySet() },
        replacesOf = { emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("Parent"), id("Parent.Nested"))
  }

  private class Item(override val mergeId: ClassId?, override val replaces: Set<ClassId>) :
    MergeContribution

  @Test
  fun `applyExcludesAndReplaces drops replaced and excluded items`() {
    val real = Item(id("Real"), setOf(id("Fake")))
    val fake = Item(id("Fake"), emptySet())
    val excludedItem = Item(id("Gone"), emptySet())
    val plain = Item(null, emptySet())

    val result =
      applyExcludesAndReplaces(
        listOf(real, fake, excludedItem, plain),
        excluded = setOf(id("Gone")),
      )

    assertThat(result).containsExactly(real, plain)
  }

  @Test
  fun `applyExcludesAndReplaces keeps everything when no excludes or replaces`() {
    val a = Item(id("A"), emptySet())
    val b = Item(id("B"), emptySet())
    assertThat(applyExcludesAndReplaces(listOf(a, b))).containsExactly(a, b).inOrder()
  }

  @Test
  fun `a lone unchanged item keeps its original list`() {
    val items = listOf(Item(id("Single"), emptySet()))

    assertThat(applyExcludesAndReplaces(items)).isSameInstanceAs(items)
  }

  @Test
  fun `a lone excluded item is removed`() {
    val item = Item(id("Single"), emptySet())

    assertThat(applyExcludesAndReplaces(listOf(item), setOf(id("Single")))).isEmpty()
  }

  @Test
  fun `a lone self-replacing item is removed by both entry points`() {
    val self = Item(id("Self"), setOf(id("Self")))
    assertThat(applyExcludesAndReplaces(listOf(self))).isEmpty()

    val plan =
      computeMergePlan(
        presentIds = setOf(id("Self")),
        excluded = emptySet(),
        replacesOf = { setOf(id("Self")) },
      )
    assertThat(plan.removed).containsExactly(id("Self"))
  }

  @Test
  fun `excluding both an origin and its generated contribution matches both`() {
    // The compiler's in-place merge can flag the generated contribution as an unmatched exclusion
    // depending on iteration order. The shared plan treats both targets as matched, and this test
    // keeps the plan's answer as the reference behavior.
    val plan =
      computeMergePlan(
        presentIds = setOf(id("AProvider"), id("B")),
        excluded = setOf(id("A"), id("AProvider")),
        originToIds = mapOf(id("A") to setOf(id("AProvider"))),
        replacesOf = { emptySet() },
      )
    assertThat(plan.removed).containsExactly(id("AProvider"))
    assertThat(plan.unmatchedExclusions).isEmpty()
  }

  private data class RankedBinding(val classId: ClassId, val typeKey: String, val rank: Long)

  @Test
  fun `higher ranked bindings replace lower ranked bindings for the same key`() {
    val low = RankedBinding(id("Low"), "Service", rank = 1)
    val high = RankedBinding(id("High"), "Service", rank = 2)

    assertThat(outrankedBindings(low, high)).containsExactly(low.classId)
  }

  @Test
  fun `equal highest ranked bindings are preserved`() {
    val low = RankedBinding(id("Low"), "Service", rank = 1)
    val first = RankedBinding(id("First"), "Service", rank = 2)
    val second = RankedBinding(id("Second"), "Service", rank = 2)

    assertThat(outrankedBindings(low, first, second)).containsExactly(low.classId)
  }

  @Test
  fun `different binding keys do not outrank each other`() {
    val low = RankedBinding(id("Low"), "FirstService", rank = 1)
    val high = RankedBinding(id("High"), "SecondService", rank = 2)

    assertThat(outrankedBindings(low, high)).isEmpty()
  }

  private fun outrankedBindings(vararg bindings: RankedBinding): Set<ClassId> =
    computeOutrankedBindings(
      bindings = bindings.toList(),
      typeKeySelector = RankedBinding::typeKey,
      rankSelector = RankedBinding::rank,
      classId = RankedBinding::classId,
    )
}
