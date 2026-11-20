// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Generates the checked-in JUnit suites. [MetroTestConfigurator] handles compiler version limits.
 */
fun main() {
  generateTestGroupSuiteWithJUnit5 {
    testGroup(
      testDataRoot = "compiler-tests/src/test/data",
      testsRoot = "compiler-tests/src/test/java",
    ) {
      val jsExcludes = listOf("interop", "circuit")
      val reportGoldenDirs = listOf("_reports")

      testClass<AbstractBoxTest> { model("box") }
      testClass<AbstractFastInitBoxTest> { model("box") }
      testClass<AbstractContributionProvidersBoxTest> { model("box") }
      testClass<AbstractIrOnlyClassesBoxTest> { model("box") }
      testClass<AbstractOmitRedundantMirrorsIrOnlyClassesBoxTest> { model("box") }

      testClass<AbstractJsBoxTest> { model("box", excludeDirsRecursively = jsExcludes) }
      testClass<AbstractJsFastInitBoxTest> { model("box", excludeDirsRecursively = jsExcludes) }
      testClass<AbstractJsContributionProvidersBoxTest> {
        model("box", excludeDirsRecursively = jsExcludes)
      }

      testClass<AbstractDiagnosticTest> {
        model("diagnostic", excludeDirsRecursively = reportGoldenDirs)
      }
      testClass<AbstractJsDiagnosticTest> {
        model("diagnostic", excludeDirsRecursively = jsExcludes + reportGoldenDirs)
      }

      testClass<AbstractFirDumpTest> { model("dump/fir") }
      testClass<AbstractIrDumpTest> { model("dump/ir") }
      testClass<AbstractReportsTest> {
        model("dump/reports", excludeDirsRecursively = reportGoldenDirs)
      }
    }
  }
}
