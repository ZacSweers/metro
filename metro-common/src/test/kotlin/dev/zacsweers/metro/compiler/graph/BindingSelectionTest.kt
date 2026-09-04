// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BindingSelectionTest {
  @Test
  fun `first result wins without evaluating later lookups`() {
    for (winner in 0..3) {
      val binding = Any()
      val visited = mutableListOf<Int>()
      fun lookup(stage: Int): Any? {
        visited += stage
        return if (stage == winner) binding else null
      }

      val selected =
        selectBinding(
          registered = { lookup(0) },
          multibinding = { lookup(1) },
          optional = { lookup(2) },
          implicit = { lookup(3) },
        )

      assertThat(selected).isSameInstanceAs(binding)
      assertThat(visited).containsExactlyElementsIn(0..winner).inOrder()
    }
  }

  @Test
  fun `missing results try every lookup once`() {
    val visited = mutableListOf<Int>()
    fun missing(stage: Int): Any? {
      visited += stage
      return null
    }

    val selected =
      selectBinding(
        registered = { missing(0) },
        multibinding = { missing(1) },
        optional = { missing(2) },
        implicit = { missing(3) },
      )

    assertThat(selected).isNull()
    assertThat(visited).containsExactly(0, 1, 2, 3).inOrder()
  }

  @Test
  fun `an empty binding result is final`() {
    val bindings = emptySet<Any>()
    val selected =
      selectBinding(
        registered = { bindings },
        multibinding = { error("Registered lookup already completed") },
        optional = { error("Registered lookup already completed") },
        implicit = { error("Registered lookup already completed") },
      )

    assertThat(selected).isSameInstanceAs(bindings)
  }
}
