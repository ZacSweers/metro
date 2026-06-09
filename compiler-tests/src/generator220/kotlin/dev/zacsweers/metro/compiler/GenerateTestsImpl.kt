// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.generators.TestGroup.TestClass
import org.jetbrains.kotlin.generators.generateTestGroupSuiteWithJUnit5

inline fun <
  reified Box,
  reified FastInitBox,
  reified ContributionProvidersBox,
  reified JsBox,
  reified JsFastInitBox,
  reified JsContributionProvidersBox,
  reified Diagnostic,
  reified FirDump,
  reified IrDump,
  reified Reports,
> generateTests(exclusionPattern: String?) {
  generateTestGroupSuiteWithJUnit5 {
    testGroup(
      testDataRoot = "compiler-tests/src/test/data",
      testsRoot = "compiler-tests/src/test/java",
    ) {
      val commonModel: TestClass.(name: String) -> Unit = { name ->
        model(name, excludedPattern = exclusionPattern)
      }
      val nonJvmModel: TestClass.(name: String) -> Unit = { name ->
        model(name, excludedPattern = exclusionPattern, excludeDirsRecursively = listOf("interop"))
      }
      testClass<Box> { commonModel("box") }
      testClass<FastInitBox> { commonModel("box") }
      testClass<ContributionProvidersBox> { commonModel("box") }
      testClass<JsBox> { nonJvmModel("box") }
      testClass<JsFastInitBox> { nonJvmModel("box") }
      testClass<JsContributionProvidersBox> { nonJvmModel("box") }
      testClass<Diagnostic> { commonModel("diagnostic") }
      testClass<FirDump> { commonModel("dump/fir") }
      testClass<IrDump> { commonModel("dump/ir") }
      testClass<Reports> { commonModel("dump/reports") }
    }
  }
}
