// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.DiagnosticsDirectives.RENDER_IR_DIAGNOSTICS_FULL_TEXT
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.RUN_PIPELINE_TILL
import org.jetbrains.kotlin.test.runners.AbstractPhasedJvmDiagnosticLightTreeTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.TestPhase

open class AbstractIrDiagnosticTest : AbstractPhasedJvmDiagnosticLightTreeTest() {
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
        +RENDER_IR_DIAGNOSTICS_FULL_TEXT
        // FIR2IR is the phase that IR transformers run in. We don't want to run till BACKEND
        // because that would (expectedly) fail
        RUN_PIPELINE_TILL.with(TestPhase.FIR2IR)
      }
    }
  }
}
