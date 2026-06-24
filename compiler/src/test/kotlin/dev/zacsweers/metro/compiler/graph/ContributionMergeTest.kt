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
}
