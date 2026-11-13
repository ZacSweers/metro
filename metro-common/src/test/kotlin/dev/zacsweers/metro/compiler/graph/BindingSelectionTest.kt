// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BindingSelectionTest {
  @Test
  fun `first result wins without evaluating later lookups`() {
    for (winner in 0..4) {
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
          derived = { lookup(3) },
          implicit = { lookup(4) },
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
        derived = { missing(3) },
        implicit = { missing(4) },
      )

    assertThat(selected).isNull()
    assertThat(visited).containsExactly(0, 1, 2, 3, 4).inOrder()
  }

  @Test
  fun `an empty binding result is final`() {
    val bindings = emptySet<Any>()
    val selected =
      selectBinding(
        registered = { bindings },
        multibinding = { error("Registered lookup already completed") },
        optional = { error("Registered lookup already completed") },
        derived = { error("Registered lookup already completed") },
        implicit = { error("Registered lookup already completed") },
      )

    assertThat(selected).isSameInstanceAs(bindings)
  }

  @Test
  fun `derived runs after optional and before implicit`() {
    val derivedBinding = Any()
    val visited = mutableListOf<String>()
    val selected =
      selectBinding(
        registered = {
          visited += "registered"
          null
        },
        multibinding = {
          visited += "multibinding"
          null
        },
        optional = {
          visited += "optional"
          null
        },
        derived = {
          visited += "derived"
          derivedBinding
        },
        implicit = { error("Derived lookup already completed") },
      )

    assertThat(selected).isSameInstanceAs(derivedBinding)
    assertThat(visited)
      .containsExactly("registered", "multibinding", "optional", "derived")
      .inOrder()
  }
}
