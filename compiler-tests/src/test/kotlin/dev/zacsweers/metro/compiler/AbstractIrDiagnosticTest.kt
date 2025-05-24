// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.cli.CliArtifact
import org.jetbrains.kotlin.test.cli.CliDirectives.CHECK_COMPILER_OUTPUT
import org.jetbrains.kotlin.test.cli.CliMetaInfoHandler
import org.jetbrains.kotlin.test.cli.CliOutputHandler
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.runners.AbstractPhasedJvmDiagnosticLightTreeTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractIrDiagnosticTest : AbstractPhasedJvmDiagnosticLightTreeTest() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      configurePlugin()

      // TODO does this get CLI output files directly? Seems not
      handlersStep(CliArtifact.Kind) { useHandlers(::CliMetaInfoHandler, ::CliOutputHandler) }
      enableMetaInfoHandler()

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
        +WITH_STDLIB
        +CHECK_COMPILER_OUTPUT

        +IGNORE_DEXING // Avoids loading R8 from the classpath.
      }
    }
  }
}
