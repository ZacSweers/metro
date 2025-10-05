// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.findParameterByName
import kotlin.reflect.jvm.kotlinFunction
import org.jetbrains.kotlin.generators.dsl.TestGroupSuite

suspend fun generate(init: TestGroupSuite.() -> Unit) {
  val function =
    try {
      val packageNames =
        listOf(
          // Kotlin 2.3.x
          "org.jetbrains.kotlin.generators.dsl.junit5",
          // Kotlin 2.2.x
          "org.jetbrains.kotlin.generators",
        )
      packageNames.firstNotNullOf { packageName ->
        Class.forName("$packageName.TestGenerationDSLForJUnit5Kt").methods.first {
          it.name == "generateTestGroupSuiteWithJUnit5" &&
            // Get the overload with the dry-run param
            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
      }
    } catch (t: Throwable) {
      System.err.println(
        "Could not find generateTestGroupSuiteWithJUnit5 function for the current kotlin version"
      )
      throw t
    }

  val kFunction = function.kotlinFunction!!
  val initParam = kFunction.findParameterByName("init")!!
  function.kotlinFunction!!.callSuspendBy(mapOf(initParam to init))
}

// Suspend because I can't get the regular callBy() function to index
suspend fun main() {
  generate {
    testGroup(
      testDataRoot = "compiler-tests/src/test/data",
      testsRoot = "compiler-tests/src/test/java",
    ) {
      testClass<AbstractBoxTest> { model("box") }
      testClass<AbstractDiagnosticTest> { model("diagnostic") }
      testClass<AbstractFirDumpTest> { model("dump/fir") }
      testClass<AbstractIrDumpTest> { model("dump/ir") }
    }
  }
}
