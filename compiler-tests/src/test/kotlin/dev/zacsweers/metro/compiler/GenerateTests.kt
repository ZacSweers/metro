// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/**
 * Generates the JUnit suites into the directory given as the only argument. The `generateTests`
 * Gradle task runs this before the test sources compile. [MetroTestConfigurator] handles compiler
 * version limits.
 *
 * The test data root stays relative to the repository root because the suites embed it in their
 * `@TestMetadata` paths.
 */
fun main(args: Array<String>) {
  val testsRoot = requireNotNull(args.singleOrNull()) { "Expected the output directory argument" }

  generateTestGroupSuiteWithJUnit5 {
    testGroup(testDataRoot = "compiler-tests/src/test/data", testsRoot = testsRoot) {
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
