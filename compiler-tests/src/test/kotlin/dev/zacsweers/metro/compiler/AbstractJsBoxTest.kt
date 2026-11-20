// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import org.jetbrains.kotlin.test.builders.RegisteredDirectivesBuilder
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJsBoxTest : KotlinJsBoxTestBase() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return EnvironmentBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      configurePlugin()

      useSourcePreprocessor(::KotlinTestImportPreprocessor)

      useMetaTestConfigurators(::MetroTestConfigurator)

      defaultDirectives {
        commonMetroTestDirectives()
        configureVariant()
        +WITH_STDLIB
      }
    }
  }

  /** The standard JS suite follows the Gradle defaults for generated classes and mirrors. */
  protected open fun RegisteredDirectivesBuilder.configureVariant() {
    if (TEST_COMPILER_TOOLING_VERSION >= MIN_IR_CLASS_GENERATION_VERSION) {
      MetroDirectives.GENERATE_CLASSES_IN_IR.with(true)
    }
    if (TEST_COMPILER_TOOLING_VERSION >= MIN_OMIT_MIRRORS_VERSION) {
      MetroDirectives.OMIT_REDUNDANT_MIRRORS.with(true)
    }
  }
}

private val MIN_OMIT_MIRRORS_VERSION = KotlinToolingVersion("2.4.0")

open class AbstractJsFastInitBoxTest : AbstractJsBoxTest() {
  override fun RegisteredDirectivesBuilder.configureVariant() {
    MetroDirectives.ENABLE_SWITCHING_PROVIDERS.with(true)
  }
}

open class AbstractJsContributionProvidersBoxTest : AbstractJsBoxTest() {
  override fun RegisteredDirectivesBuilder.configureVariant() {
    // Top-level contribution providers on JS require Kotlin 2.3.21 or newer.
    MetroDirectives.MIN_COMPILER_VERSION.with("2.3.21")
    MetroDirectives.GENERATE_CONTRIBUTION_HINTS.with(true)
    +MetroDirectives.GENERATE_CONTRIBUTION_HINTS_IN_FIR
    MetroDirectives.GENERATE_CONTRIBUTION_PROVIDERS.with(true)
  }
}
