// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

// API repackaged in kotlin 2.3.0
inline fun <reified Box, reified Diagnostic, reified FirDump, reified IrDump> generateTests() {
  generateTestGroupSuiteWithJUnit5 {
    testGroup(
      testDataRoot = "compiler-tests/src/test/data",
      testsRoot = "compiler-tests/src/test/java",
    ) {
      testClass<Box> { model("box") }
      testClass<Diagnostic> { model("diagnostic") }
      testClass<FirDump> { model("dump/fir") }
      testClass<IrDump> { model("dump/ir") }
    }
  }
}
