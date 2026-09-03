// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BindingSelectionTest {
  @Test
  fun `explicit bindings stop generated and implicit lookups`() {
    val visited = mutableListOf<BindingTier>()
    val selected = selectBindingTier { tier ->
      visited += tier
      when (tier) {
        BindingTier.ASSISTED_TARGET -> false
        BindingTier.EXPLICIT -> true
        else -> error("Lower tiers should stay unevaluated")
      }
    }

    assertThat(selected).isEqualTo(BindingTier.EXPLICIT)
    assertThat(visited).containsExactly(BindingTier.ASSISTED_TARGET, BindingTier.EXPLICIT).inOrder()
  }

  @Test
  fun `assisted targets take precedence over explicit providers`() {
    assertThat(
        selectBindingTier { it == BindingTier.ASSISTED_TARGET || it == BindingTier.EXPLICIT }
      )
      .isEqualTo(BindingTier.ASSISTED_TARGET)
  }

  @Test
  fun `fallback tiers keep their lookup order`() {
    val available =
      linkedSetOf(
        BindingTier.GENERATED_GRAPH,
        BindingTier.MULTIBINDING,
        BindingTier.OPTIONAL,
        BindingTier.IMPLICIT,
      )
    while (available.isNotEmpty()) {
      val expected = available.first()
      assertThat(selectBindingTier(available::contains)).isEqualTo(expected)
      available -= expected
    }
    assertThat(selectBindingTier(available::contains)).isNull()
  }
}
