// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.FIR_DUMP
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.RUN_PIPELINE_TILL
import org.jetbrains.kotlin.test.services.TestPhase

open class AbstractFirDumpTest : AbstractDiagnosticTest(), LeakPatchingTest by LeakPatchingTest() {
  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      registerLeakPatcher()
      defaultDirectives {
        RUN_PIPELINE_TILL.with(TestPhase.FRONTEND)
        +FIR_DUMP
        +DISABLE_GENERATED_FIR_TAGS
      }

      useMetaTestConfigurators(::MetroTestConfigurator)
    }
  }
}
