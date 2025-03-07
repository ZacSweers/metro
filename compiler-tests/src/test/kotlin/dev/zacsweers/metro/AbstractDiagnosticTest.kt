// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.runners.AbstractFirLightTreeDiagnosticsTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractDiagnosticTest : AbstractFirLightTreeDiagnosticsTest() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      configurePlugin()

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
      }
    }
  }
}
