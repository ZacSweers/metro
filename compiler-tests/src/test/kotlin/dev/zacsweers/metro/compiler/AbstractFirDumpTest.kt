// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.FIR_DUMP

/** Runs the diagnostic pipeline with FIR dumps enabled. */
open class AbstractFirDumpTest : AbstractDiagnosticTest() {
  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)
    builder.defaultDirectives { +FIR_DUMP }
  }
}
