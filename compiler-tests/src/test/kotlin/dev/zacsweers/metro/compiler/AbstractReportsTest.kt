// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.runners.AbstractFirLightTreeDiagnosticsTestWithJvmIrBackend
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

/**
 * Base test class for verifying Metro report outputs.
 *
 * This class runs through the full compilation pipeline (FIR + IR) to generate reports without
 * requiring any dump files.
 *
 * Usage:
 * ```kotlin
 * // CHECK_REPORTS: merging-unmatched-exclusions-fir/test/AppGraph
 * // CHECK_REPORTS: merging-unmatched-replacements-ir/test/AppGraph
 *
 * @DependencyGraph
 * interface AppGraph { ... }
 * ```
 *
 * Expected files should be named `<testFile>/<diagnosticKey>/<path>/<reportName>.txt` alongside the
 * test data.
 */
open class AbstractReportsTest : AbstractFirLightTreeDiagnosticsTestWithJvmIrBackend() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)
    with(builder) {
      configurePlugin()

      useMetaTestConfigurators(::MetroTestConfigurator)

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
        +WITH_STDLIB
        +IGNORE_DEXING
      }
    }
  }
}
