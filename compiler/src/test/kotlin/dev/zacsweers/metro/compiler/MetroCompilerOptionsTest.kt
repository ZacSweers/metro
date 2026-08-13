// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Test

class MetroCompilerOptionsTest {

  private val compatContext by lazy { CompatContext.create() }

  @Test
  fun `FIR contribution hint defaults follow compiler capabilities when option is absent`() {
    for (version in listOf("2.3.20-Beta1", "2.3.20-dev-6204", "2.3.21")) {
      assertThat(loadOptions(version).generateContributionHintsInFir).isTrue()
    }
    for (version in listOf("2.3.10", "2.3.20-dev-6203")) {
      assertThat(loadOptions(version).generateContributionHintsInFir).isFalse()
    }
  }

  @Test
  fun `FIR contribution hints default to FIR in IDE mode`() {
    for (version in listOf("2.3.20-ij253-87", "2.3.255-dev-255")) {
      assertThat(loadOptions(version, isIde = true).generateContributionHintsInFir).isTrue()
    }
  }

  @Test
  fun `explicit FIR contribution hint option overrides compiler default`() {
    val version = "2.3.21"
    val options =
      loadOptions(version) {
        MetroOption.GENERATE_CONTRIBUTION_HINTS_IN_FIR.raw.put(this, "false")
      }

    assertThat(options.generateContributionHintsInFir).isFalse()
    assertThat(validationErrors(version, options))
      .containsExactly(
        "generateContributionHintsInFir cannot be disabled when generateContributionHints is " +
          "enabled on Kotlin $version."
      )
  }

  @Test
  fun `disabled contribution hints leave FIR hint generation disabled`() {
    val options =
      loadOptions("2.3.21") {
        MetroOption.GENERATE_CONTRIBUTION_HINTS.raw.put(this, "false")
      }

    assertThat(options.generateContributionHints).isFalse()
    assertThat(options.generateContributionHintsInFir).isFalse()
  }

  @Test
  fun `explicit FIR contribution hint option overrides IDE default`() {
    val options =
      loadOptions("2.3.20-ij253-87", isIde = true) {
        MetroOption.GENERATE_CONTRIBUTION_HINTS_IN_FIR.raw.put(this, "false")
      }

    assertThat(options.generateContributionHintsInFir).isFalse()
  }

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
    for (version in listOf("2.3.10", "2.3.20-dev-6203")) {
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

  private fun loadOptions(
    compilerVersion: String,
    isIde: Boolean = false,
    configure: CompilerConfiguration.() -> Unit = {},
  ): MetroOptions {
    val version = KotlinToolingVersion(compilerVersion)
    val configuration = createCompilerConfiguration().apply(configure)
    return MetroOptions.load(configuration, version, isIde)
  }

  private fun validationErrors(
    compilerVersion: String,
    generateContributionHints: Boolean = true,
    generateContributionHintsInFir: Boolean = false,
  ): List<String> {
    val options = MetroOptions.buildOptions {
      this.generateContributionHints = generateContributionHints
      this.generateContributionHintsInFir = generateContributionHintsInFir
    }
    return validationErrors(compilerVersion, options)
  }

  private fun validationErrors(compilerVersion: String, options: MetroOptions): List<String> {
    val errors = mutableListOf<String>()
    val configuration = createCompilerConfiguration()

    val valid =
      options.validate(KotlinToolingVersion(compilerVersion), configuration) { error ->
        errors += error
      }

    assertThat(valid).isEqualTo(errors.isEmpty())
    return errors
  }

  private fun createCompilerConfiguration(): CompilerConfiguration {
    return with(compatContext) { createCompilerConfigurationCompat() }
  }
}
