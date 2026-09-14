// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.dsl.TestGroup
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/** Generates suites with the non-null test class types required by the Kotlin test framework. */
// API repackaged in kotlin 2.3.0
inline fun <
  reified Box : Any,
  reified FastInitBox : Any,
  reified ContributionProvidersBox : Any,
  reified JsBox : Any,
  reified JsFastInitBox : Any,
  reified JsContributionProvidersBox : Any,
  reified IrOnlyClassesBox : Any,
  reified OmitRedundantMirrorsIrOnlyClassesBox : Any,
  reified Diagnostic : Any,
  reified JsDiagnostic : Any,
  reified FirDump : Any,
  reified IrDump : Any,
  reified Reports : Any,
> generateTests(exclusionPattern: String?) {
  generateTestGroupSuiteWithJUnit5 {
    testGroup(
      testDataRoot = "compiler-tests/src/test/data",
      testsRoot = "compiler-tests/src/test/java",
    ) {
      val commonModel: TestGroup.TestClass.(name: String) -> Unit = { name ->
        model(name, excludedPattern = exclusionPattern)
      }
      val nonJvmModel: TestGroup.TestClass.(name: String) -> Unit = { name ->
        model(
          name,
          excludedPattern = exclusionPattern,
          excludeDirsRecursively = listOf("interop", "circuit"),
        )
      }
      val diagnosticModel: TestGroup.TestClass.(name: String) -> Unit = { name ->
        model(
          name,
          excludedPattern = exclusionPattern,
          excludeDirsRecursively = listOf("_reports"),
        )
      }
      val nonJvmDiagnosticModel: TestGroup.TestClass.(name: String) -> Unit = { name ->
        model(
          name,
          excludedPattern = exclusionPattern,
          excludeDirsRecursively = listOf("interop", "circuit", "_reports"),
        )
      }
      testClass<Box> { commonModel("box") }
      testClass<FastInitBox> { commonModel("box") }
      testClass<ContributionProvidersBox> { commonModel("box") }
      testClass<JsBox> { nonJvmModel("box") }
      testClass<JsFastInitBox> { nonJvmModel("box") }
      testClass<JsContributionProvidersBox> { nonJvmModel("box") }
      testClass<IrOnlyClassesBox> { commonModel("box") }
      testClass<OmitRedundantMirrorsIrOnlyClassesBox> { commonModel("box") }
      testClass<Diagnostic> { diagnosticModel("diagnostic") }
      testClass<JsDiagnostic> { nonJvmDiagnosticModel("diagnostic") }
      testClass<FirDump> { commonModel("dump/fir") }
      testClass<IrDump> { commonModel("dump/ir") }
      testClass<Reports> { commonModel("dump/reports") }
    }
  }
}
