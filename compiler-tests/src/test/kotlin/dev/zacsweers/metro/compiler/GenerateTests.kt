// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.generateTestGroupSuiteWithJUnit5

fun main() {
  generateTestGroupSuiteWithJUnit5 {
    testGroup(
      testDataRoot = "compiler-tests/src/test/data",
      testsRoot = "compiler-tests/src/test/java",
    ) {
      testClass<AbstractBoxTest> { model("box") }
      testClass<AbstractFirDiagnosticTest> { model("diagnostic/fir") }
      testClass<AbstractIrDiagnosticTest> { model("diagnostic/ir") }
      testClass<AbstractFirDumpTest> { model("dump/fir") }
      testClass<AbstractIrDumpTest> { model("dump/ir") }
    }
  }
}
