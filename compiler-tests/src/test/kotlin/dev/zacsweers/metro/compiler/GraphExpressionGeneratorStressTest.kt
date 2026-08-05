// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test

/** Exercises deeply nested binding expressions without committing thousands of fixture classes. */
class GraphExpressionGeneratorStressTest : AbstractBoxTest() {

  @Test
  fun deepAcyclicConstructorChain() {
    runGeneratedGraphTest(
      testName = "DeepAcyclicConstructorChain",
      bindingCount = 2_000,
      closesCycleWithProvider = false,
    )
  }

  @Test
  fun providerBrokenCycle() {
    runGeneratedGraphTest(
      testName = "ProviderBrokenCycle",
      bindingCount = 2_000,
      closesCycleWithProvider = true,
    )
  }

  @Test
  fun deepBranchedConstructorChain() {
    runGeneratedGraphTest(
      testName = "DeepBranchedConstructorChain",
      bindingCount = 2_000,
      closesCycleWithProvider = false,
      hasBranchedRoot = true,
    )
  }

  @Test
  fun deepMultibindingContribution() {
    runGeneratedGraphTest(
      testName = "DeepMultibindingContribution",
      bindingCount = 2_000,
      closesCycleWithProvider = false,
      usesMultibindingRoot = true,
    )
  }

  private fun runGeneratedGraphTest(
    testName: String,
    bindingCount: Int,
    closesCycleWithProvider: Boolean,
    hasBranchedRoot: Boolean = false,
    usesMultibindingRoot: Boolean = false,
  ) {
    val testData = createTempFile(prefix = testName, suffix = ".kt")

    try {
      testData.writeText(
        buildString {
          // Unscoped constructor bindings must remain inline so properties cannot mask the deep
          // graph-expression recursion exercised by these regressions.
          for (index in 0 until bindingCount) {
            append("@Inject class Node")
            append(index.toString().padStart(4, '0'))

            if (index + 1 < bindingCount) {
              append("(dependency: Node")
              append((index + 1).toString().padStart(4, '0'))
              if (hasBranchedRoot && index == 0) {
                // A second eager constructor call must not hide the deep dependency branch.
                append(", extra: Extra")
              }
              append(')')
            } else if (closesCycleWithProvider) {
              append("(dependency: () -> Node0000)")
            }

            appendLine()
          }

          if (hasBranchedRoot) {
            appendLine("@Inject class Extra")
          }

          appendLine()
          appendLine("@DependencyGraph")
          appendLine("interface StressGraph {")
          if (usesMultibindingRoot) {
            // Two elements force buildSet { ... }, placing the deep chain inside its lambda.
            appendLine("  @Provides @IntoSet fun provideDeep(value: Node0000): Any = value")
            appendLine("  @Provides @IntoSet fun provideOther(): Any = \"other\"")
            appendLine("  val root: Set<Any>")
          } else {
            appendLine("  val root: Node0000")
          }
          appendLine("}")
          appendLine()
          appendLine("fun box(): String {")

          // Accessing the acyclic root also verifies that flattened constructors retain their
          // original runtime behavior. The provider cycle may legitimately recurse at runtime.
          appendLine("  val graph = createGraph<StressGraph>()")
          if (!closesCycleWithProvider) {
            if (usesMultibindingRoot) {
              appendLine("  check(graph.root.size == 2)")
            } else {
              appendLine("  graph.root")
            }
          }
          appendLine("  return \"OK\"")
          appendLine("}")
        }
      )

      runTest(testData.toString())
    } finally {
      testData.deleteIfExists()
    }
  }
}
