// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.jetbrains.kotlin.name.ClassId
import org.junit.Test

class ContributionMergeTest {

  private fun id(name: String): ClassId = ClassId.fromString("test/$name")

  @Test
  fun `merge plan checks for cancellation`() {
    val presentIds = buildSet { repeat(300) { index -> add(id("Contribution$index")) } }
    var checks = 0

    assertFailsWith<MergeCancellationException> {
      computeMergePlan(
        presentIds = presentIds,
        excluded = emptySet(),
        ensureActive = {
          if (++checks == 2) throw MergeCancellationException()
        },
        replacesOf = { emptySet() },
      )
    }

    assertThat(checks).isEqualTo(2)
  }

  @Test
  fun `exclude and replace filtering checks for cancellation`() {
    val items = List(300) { index -> Item(id("Contribution$index"), emptySet()) }
    var checks = 0

    assertFailsWith<MergeCancellationException> {
      applyExcludesAndReplaces(
        items = items,
        ensureActive = {
          if (++checks == 2) throw MergeCancellationException()
        },
      )
    }

    assertThat(checks).isEqualTo(2)
  }

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

  private data class PrioritizedContribution(
    val classId: ClassId,
    val conflictKey: String,
    val priority: Int = Int.MIN_VALUE,
  )

  @Test
  fun `priority filtering checks for cancellation`() {
    val contributions =
      List(300) { index -> PrioritizedContribution(id("Contribution$index"), "Service", index) }
    var checks = 0

    assertFailsWith<MergeCancellationException> {
      computeLowerPriorityContributions(
        bindings = contributions,
        ensureActive = {
          if (++checks == 2) throw MergeCancellationException()
        },
        conflictKeySelector = PrioritizedContribution::conflictKey,
        prioritySelector = PrioritizedContribution::priority,
      )
    }

    assertThat(checks).isEqualTo(2)
  }

  @Test
  fun `higher priority removes only the conflicting contribution from an origin`() {
    val losing = PrioritizedContribution(id("Original"), "FirstService", priority = 1)
    val retained = PrioritizedContribution(id("Original"), "SecondService", priority = 1)
    val replacement = PrioritizedContribution(id("Replacement"), "FirstService", priority = 2)

    assertThat(lowerPriorityContributions(losing, retained, replacement)).containsExactly(losing)
  }

  @Test
  fun `higher map priority removes only the matching map key`() {
    val losing = PrioritizedContribution(id("Original"), "handlers:shared", priority = 1)
    val retained = PrioritizedContribution(id("Original"), "handlers:other", priority = 1)
    val replacement = PrioritizedContribution(id("Replacement"), "handlers:shared", priority = 2)

    assertThat(lowerPriorityContributions(losing, retained, replacement)).containsExactly(losing)
  }

  @Test
  fun `negative priorities supersede the default priority`() {
    val default = PrioritizedContribution(id("Default"), "Service")
    val preferred = PrioritizedContribution(id("Preferred"), "Service", priority = -100)

    assertThat(lowerPriorityContributions(default, preferred)).containsExactly(default)
  }

  @Test
  fun `equally prioritized contributions remain for duplicate diagnostics`() {
    val first = PrioritizedContribution(id("First"), "Service", priority = 10)
    val second = PrioritizedContribution(id("Second"), "Service", priority = 10)

    assertThat(lowerPriorityContributions(first, second)).isEmpty()
  }

  @Test
  fun `default priority contributions are unchanged`() {
    val first = PrioritizedContribution(id("First"), "Service")
    val second = PrioritizedContribution(id("Second"), "Service")

    assertThat(lowerPriorityContributions(first, second)).isEmpty()
  }

  private fun lowerPriorityContributions(
    vararg contributions: PrioritizedContribution
  ): Set<PrioritizedContribution> =
    computeLowerPriorityContributions(
      bindings = contributions.toList(),
      conflictKeySelector = PrioritizedContribution::conflictKey,
      prioritySelector = PrioritizedContribution::priority,
    )

  @Test
  fun `equal highest priorities are preserved while lower priorities are removed`() {
    val low = PrioritizedContribution(id("Low"), "Service", priority = 1)
    val first = PrioritizedContribution(id("First"), "Service", priority = 2)
    val second = PrioritizedContribution(id("Second"), "Service", priority = 2)

    assertThat(lowerPriorityContributions(low, first, second)).containsExactly(low)
  }

  @Test
  fun `different binding keys do not compete by priority`() {
    val low = PrioritizedContribution(id("Low"), "FirstService", priority = 1)
    val high = PrioritizedContribution(id("High"), "SecondService", priority = 2)

    assertThat(lowerPriorityContributions(low, high)).isEmpty()
  }
}

private class MergeCancellationException : RuntimeException()
