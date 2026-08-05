// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfiguration.Internals
import org.junit.Test

class MetroCompilerOptionsTest {

  @Test
  fun `FIR contribution hints are required on supported compilers`() {
    for (version in listOf("2.3.20-Beta1", "2.3.20-dev-6204", "2.4.20-Beta2")) {
      assertThat(validationErrors(version))
        .containsExactly(
          "generateContributionHintsInFir cannot be disabled when generateContributionHints is " +
            "enabled on Kotlin $version."
        )
    }
  }

  @Test
  fun `IR contribution hints remain valid before supported compiler boundaries`() {
    for (version in listOf("2.3.10", "2.3.20-Alpha1", "2.3.20-dev-6203")) {
      assertThat(validationErrors(version)).isEmpty()
    }
  }

  @Test
  fun `supported compilers allow FIR hints or disabled hints`() {
    for (version in listOf("2.3.20-Beta1", "2.3.20-dev-6204")) {
      assertThat(validationErrors(version, generateContributionHintsInFir = true)).isEmpty()
      assertThat(
          validationErrors(
            version,
            generateContributionHints = false,
            generateContributionHintsInFir = false,
          )
        )
        .isEmpty()
    }
  }

  @OptIn(Internals::class)
  private fun validationErrors(
    compilerVersion: String,
    generateContributionHints: Boolean = true,
    generateContributionHintsInFir: Boolean = false,
  ): List<String> {
    val options = MetroOptions.buildOptions {
      this.generateContributionHints = generateContributionHints
      this.generateContributionHintsInFir = generateContributionHintsInFir
    }
    val errors = mutableListOf<String>()

    val valid =
      options.validate(KotlinToolingVersion(compilerVersion), CompilerConfiguration()) { error ->
        errors += error
      }

    assertThat(valid).isEqualTo(errors.isEmpty())
    return errors
  }
}
