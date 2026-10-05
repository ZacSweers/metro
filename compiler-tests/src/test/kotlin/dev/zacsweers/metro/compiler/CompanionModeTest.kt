// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the compiler-only mode spelling and default contracts. */
class CompanionModeTest {
  @Test
  fun defaultModeUsesCompanionObject() {
    assertEquals(CompanionMode.COMPANION_OBJECT, MetroOptions().companionMode)
  }

  @Test
  fun acceptsEnumAndCompilerOptionSpellings() {
    for (mode in CompanionMode.entries) {
      val optionSpelling = mode.name.lowercase().replace('_', '-')
      assertEquals(mode, CompanionMode.parse(mode.name))
      assertEquals(mode, CompanionMode.parse(optionSpelling))
      assertEquals(mode, CompanionMode.parse(" ${optionSpelling.uppercase()} "))

      val parsedValue = MetroOption.COMPANION_MODE.raw.valueMapper(optionSpelling)
      val options = MetroOptions.buildOptions {
        applyOptionValue(MetroOption.COMPANION_MODE, parsedValue)
      }
      assertEquals(mode, options.companionMode)
      assertEquals(mode, options.toBuilder().build().companionMode)
    }
  }

  @Test
  fun rejectsUnknownMode() {
    val error = assertFailsWith<IllegalArgumentException> { CompanionMode.parse("companion") }
    assertTrue(error.message.orEmpty().contains("companion-mode"))
    assertFailsWith<IllegalArgumentException> {
      MetroOption.COMPANION_MODE.raw.valueMapper("unknown")
    }
  }

  @Test
  fun companionModesRequireCompanionBlocks() {
    assertFalse(CompanionMode.COMPANION_OBJECT.requiresCompanionBlocks)
    assertFalse(CompanionMode.NONE.requiresCompanionBlocks)
    assertTrue(CompanionMode.COMPANION_BLOCK.requiresCompanionBlocks)
    assertTrue(CompanionMode.COMPATIBILITY.requiresCompanionBlocks)
  }
}
