// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.name.ClassId
import org.junit.Test

class MetroCompilerOptionsTest {

  private val compatContext by lazy { CompatContext.create() }

  @Test
  fun `function injection uses the configured inject annotations without an override`() {
    for (enabled in listOf(false, true)) {
      val options = MetroOptions.buildOptions {
        enableTopLevelFunctionInjection = enabled
        applyRawOption("custom-inject", "test/CustomInject")
      }

      assertThat(options.topLevelFunctionInjectAnnotations)
        .containsExactly(MetroClassIds.inject, ClassId.fromString("test/CustomInject"))
    }
  }

  @Test
  fun `function injection override replaces annotations only for functions`() {
    val options = MetroOptions.buildOptions {
      enableTopLevelFunctionInjection = true
      applyRawOptions(
        mapOf(
          "custom-inject" to "test/CustomInject",
          "function-inject-annotations-override" to "test/InjectFunction:test/OtherInjectFunction",
        ),
      )
    }

    assertThat(options.topLevelFunctionInjectAnnotations)
      .containsExactly(
        ClassId.fromString("test/InjectFunction"),
        ClassId.fromString("test/OtherInjectFunction"),
      )
    assertThat(options.injectAnnotations)
      .containsExactly(MetroClassIds.inject, ClassId.fromString("test/CustomInject"))
    assertThat(options.toBuilder().build().topLevelFunctionInjectAnnotations)
      .containsExactlyElementsIn(options.topLevelFunctionInjectAnnotations)
  }

  @Test
  fun `disabled function injection ignores the annotation override`() {
    val options = MetroOptions.buildOptions {
      enableTopLevelFunctionInjection = false
      applyRawOption("custom-inject", "test/CustomInject")
      applyRawOption("function-inject-annotations-override", "test/InjectFunction")
    }

    assertThat(options.topLevelFunctionInjectAnnotations)
      .containsExactly(MetroClassIds.inject, ClassId.fromString("test/CustomInject"))
  }

  @Test
  fun `empty function injection override disables function annotation discovery`() {
    val options = MetroOptions.buildOptions {
      enableTopLevelFunctionInjection = true
      applyRawOption("function-inject-annotations-override", "")
    }

    assertThat(options.topLevelFunctionInjectAnnotations).isEmpty()
    assertThat(options.toBuilder().build().topLevelFunctionInjectAnnotations).isEmpty()
    assertThat(options.injectAnnotations).containsExactly(MetroClassIds.inject)
  }

  @Test
  fun `redundant mirror defaults follow the Kotlin version`() {
    for (version in listOf("2.4.0", "2.4.10", "2.4.20-dev-6138", "2.4.20", "2.5.0-Beta1")) {
      assertThat(loadOptions(version).omitRedundantMirrors).isTrue()
      assertThat(loadOptions(version, isIde = true).omitRedundantMirrors).isTrue()
    }
    for (version in listOf("2.3.0", "2.3.21", "2.4.0-dev-2633", "2.4.0-Beta2", "2.4.0-RC3")) {
      assertThat(loadOptions(version).omitRedundantMirrors).isFalse()
      assertThat(loadOptions(version, isIde = true).omitRedundantMirrors).isFalse()
    }
  }

  @Test
  fun `unknown compiler versions preserve redundant mirrors by default`() {
    assertThat(loadOptions(null).omitRedundantMirrors).isFalse()
    assertThat(loadOptions(null, isIde = true).omitRedundantMirrors).isFalse()
  }

  @Test
  fun `explicit redundant mirror options override compiler defaults`() {
    for (version in listOf("2.3.21", "2.4.0", "2.4.20", null)) {
      for (enabled in listOf(false, true)) {
        val options =
          loadOptions(version) {
            MetroOption.OMIT_REDUNDANT_MIRRORS.raw.put(this, enabled.toString())
          }

        assertThat(options.omitRedundantMirrors).isEqualTo(enabled)
      }
    }
  }

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
          "enabled on Kotlin $version.",
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
            "enabled on Kotlin $version.",
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
          ),
        )
        .isEmpty()
    }
  }

  private fun loadOptions(
    compilerVersion: String?,
    isIde: Boolean = false,
    configure: CompilerConfiguration.() -> Unit = {},
  ): MetroOptions {
    val version = compilerVersion?.let(::KotlinToolingVersion)
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
