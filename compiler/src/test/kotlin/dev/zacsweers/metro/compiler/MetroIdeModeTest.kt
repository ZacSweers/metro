// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.CompilerVersionAliases
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.junit.Test

class MetroIdeModeTest {
  private val compatContext by lazy { CompatContext.create() }

  @Test
  fun `older compilers use legacy detection even when the analysis flag disagrees`() {
    for (version in listOf("2.3.0", "2.3.20", "2.4.0", "2.4.10")) {
      for (legacyIsIde in listOf(false, true)) {
        val configuration = configuration(ideMode = !legacyIsIde)
        assertThat(configuration.isIdeMode(KotlinToolingVersion(version)) { legacyIsIde })
          .isEqualTo(legacyIsIde)
      }
    }
  }

  // https://github.com/ZacSweers/metro/issues/2845
  @Test
  fun `modern compilers use the analysis flag without probing the classpath`() {
    for (version in listOf("2.4.20", "2.4.21", "2.5.0-dev-7834", "3.0.0")) {
      for (ideMode in listOf(false, true)) {
        val configuration = configuration(ideMode)
        val actual =
          configuration.isIdeMode(KotlinToolingVersion(version)) {
            error("Modern compilers must not probe the classpath")
          }
        assertThat(actual).isEqualTo(ideMode)
      }
    }
  }

  @Test
  fun `modern compilers with missing settings do not fall back to the classpath`() {
    val actual =
      configuration().isIdeMode(KotlinToolingVersion("2.4.20")) {
        error("Modern compilers must not probe the classpath")
      }
    assertThat(actual).isFalse()
  }

  @Test
  fun `prereleases below the stable cutoff retain legacy detection`() {
    for (version in listOf("2.4.20-dev-6725", "2.4.20-Beta1", "2.4.20-RC", "2.4.20-ij262-52")) {
      assertThat(configuration().isIdeMode(KotlinToolingVersion(version)) { true }).isTrue()
    }
  }

  @Test
  fun `unknown compilers retain legacy detection`() {
    for (legacyIsIde in listOf(false, true)) {
      assertThat(configuration(ideMode = !legacyIsIde).isIdeMode(null) { legacyIsIde })
        .isEqualTo(legacyIsIde)
    }
  }

  @Test
  fun `IDE version aliases select the legacy path`() {
    for (rawVersion in listOf("2.3.20-ij253-87", "2.3.255-dev-255", "2.4.255-dev-255")) {
      val version = checkNotNull(CompilerVersionAliases.map(KotlinToolingVersion(rawVersion)))
      assertThat(configuration().isIdeMode(version) { true }).isTrue()
    }
  }

  @Test
  fun `legacy IDE detection enables FIR hints for contribution providers`() {
    val version = checkNotNull(CompilerVersionAliases.map(KotlinToolingVersion("2.3.20-ij253-87")))
    val configuration = configuration()
    MetroOption.GENERATE_CONTRIBUTION_PROVIDERS.raw.put(configuration, "true")
    val isIde = configuration.isIdeMode(version) { true }
    val options = MetroOptions.load(configuration, version, isIde)
    val errors = mutableListOf<String>()

    val valid = options.validate(version, configuration) { errors += it }

    assertThat(options.generateContributionHintsInFir).isTrue()
    assertThat(errors).isEmpty()
    assertThat(valid).isTrue()
  }

  private fun configuration(ideMode: Boolean? = null): CompilerConfiguration {
    val configuration = with(compatContext) { createCompilerConfigurationCompat() }
    if (ideMode != null) {
      configuration.languageVersionSettings =
        LanguageVersionSettingsImpl(
          LanguageVersion.LATEST_STABLE,
          ApiVersion.LATEST_STABLE,
          mapOf(AnalysisFlags.ideMode to ideMode),
        )
    }
    return configuration
  }
}
