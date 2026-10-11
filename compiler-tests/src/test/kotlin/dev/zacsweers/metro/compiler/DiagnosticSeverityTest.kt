// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.WarningLevel
import org.jetbrains.kotlin.diagnostics.AbstractKtDiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors

/** Exercises the severity contract against the compiler selected for this test run. */
class DiagnosticSeverityTest {
  @Test
  fun defaultWarning() {
    assertEquals(Severity.WARNING, effectiveSeverity())
  }

  @Test
  fun defaultError() {
    assertEquals(Severity.ERROR, effectiveSeverity(factory = FirErrors.UNRESOLVED_REFERENCE))
  }

  @Test
  fun disabledWarning() {
    assertNull(effectiveSeverity(WarningLevel.Disabled))
  }

  @Test
  fun promotedWarning() {
    assertEquals(Severity.ERROR, effectiveSeverity(WarningLevel.Error))
  }

  @Test
  fun explicitWarning() {
    // FIXED_WARNING preserves an explicit warning override under -Werror.
    assertEquals(Severity.FIXED_WARNING, effectiveSeverity(WarningLevel.Warning))
  }

  private fun effectiveSeverity(
    level: WarningLevel? = null,
    factory: AbstractKtDiagnosticFactory = FirErrors.UNUSED_VARIABLE,
  ): Severity? {
    val warningLevels =
      if (level == null) {
        emptyMap()
      } else {
        mapOf(factory.name to level)
      }
    val settings =
      LanguageVersionSettingsImpl(
        LanguageVersion.LATEST_STABLE,
        ApiVersion.LATEST_STABLE,
        analysisFlags = mapOf(AnalysisFlags.warningLevels to warningLevels),
      )
    return with(CompatContext.create()) { factory.getEffectiveSeverityCompat(settings) }
  }
}
