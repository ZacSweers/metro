// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.dsl.TestGroup
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

// API repackaged in kotlin 2.3.0
inline fun <
  reified Box,
  reified FastInitBox,
  reified ContributionProvidersBox,
  reified JsBox,
  reified JsFastInitBox,
  reified JsContributionProvidersBox,
  reified IrOnlyClassesBox,
  reified Diagnostic,
  reified JsDiagnostic,
  reified FirDump,
  reified IrDump,
  reified Reports,
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
      testClass<Box> { commonModel("box") }
      testClass<FastInitBox> { commonModel("box") }
      testClass<ContributionProvidersBox> { commonModel("box") }
      testClass<JsBox> { nonJvmModel("box") }
      testClass<JsFastInitBox> { nonJvmModel("box") }
      testClass<JsContributionProvidersBox> { nonJvmModel("box") }
      testClass<IrOnlyClassesBox> { commonModel("box") }
      testClass<Diagnostic> { commonModel("diagnostic") }
      testClass<JsDiagnostic> { nonJvmModel("diagnostic") }
      testClass<FirDump> { commonModel("dump/fir") }
      testClass<IrDump> { commonModel("dump/ir") }
      testClass<Reports> { commonModel("dump/reports") }
    }
  }
}
