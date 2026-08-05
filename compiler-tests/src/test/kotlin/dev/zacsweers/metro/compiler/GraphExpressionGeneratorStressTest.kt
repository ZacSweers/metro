// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Tests deep dependency graphs without adding thousands of fixture classes.
 *
 * The `StressTest` suffix keeps these tests behind `-Pmetro.enableLargeTests`.
 */
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
  fun deepTransitivelySuspendConstructorChain() {
    runGeneratedGraphTest(
      testName = "DeepTransitivelySuspendConstructorChain",
      bindingCount = 2_000,
      closesCycleWithProvider = false,
      usesSuspendBindings = true,
    )
  }

  @Test
  fun deepSuspendChainWithSynchronousBranch() {
    // The suspend value and 63 nodes put the root exactly at the first depth-limiting getter.
    // Creating its suspend factory then requests the previously scalar branch as providers.
    runGeneratedGraphTest(
      testName = "DeepSuspendChainWithSynchronousBranch",
      bindingCount = 63,
      closesCycleWithProvider = false,
      usesSuspendBindings = true,
      synchronousBranchBindingCount = 2_000,
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

  /** Writes a temporary graph fixture and runs it through the standard box test runner. */
  private fun runGeneratedGraphTest(
    testName: String,
    bindingCount: Int,
    closesCycleWithProvider: Boolean,
    hasBranchedRoot: Boolean = false,
    usesMultibindingRoot: Boolean = false,
    usesSuspendBindings: Boolean = false,
    synchronousBranchBindingCount: Int = 0,
  ) {
    val testData = createTempFile(prefix = testName, suffix = ".kt")

    try {
      testData.writeText(
        buildString {
          appendLine("// ENABLE_STACKLESS_GRAPH_GEN")
          if (usesSuspendBindings) {
            appendLine("// ENABLE_SUSPEND_PROVIDERS")
          }

          // Keep bindings unscoped so graph fields do not shorten the generated call chain.
          for (index in 0 until bindingCount) {
            append("@Inject class Node")
            append(index.toString().padStart(4, '0'))

            if (index + 1 < bindingCount) {
              append("(dependency: Node")
              append((index + 1).toString().padStart(4, '0'))
              if (hasBranchedRoot && index == 0) {
                // Add another dependency to make sure the deep branch is still checked.
                append(", extra: Extra")
              }
              if (synchronousBranchBindingCount > 0 && index == 0) {
                append(", synchronousDependency: SynchronousNode0000, suspendValue: String")
              }
              append(')')
            } else if (closesCycleWithProvider) {
              append("(dependency: () -> Node0000)")
            } else if (usesSuspendBindings) {
              append("(dependency: String)")
            }

            appendLine()
          }

          if (synchronousBranchBindingCount > 0) {
            appendLine()
            // This branch starts as scalar but becomes a provider dependency of a suspend factory.
            for (index in 0 until synchronousBranchBindingCount) {
              append("@Inject class SynchronousNode")
              append(index.toString().padStart(4, '0'))
              if (index + 1 < synchronousBranchBindingCount) {
                append("(dependency: SynchronousNode")
                append((index + 1).toString().padStart(4, '0'))
                append(')')
              }
              appendLine()
            }
          }

          if (hasBranchedRoot) {
            appendLine("@Inject class Extra")
          }

          appendLine()
          appendLine("@DependencyGraph")
          appendLine("interface StressGraph {")
          if (usesMultibindingRoot) {
            // Two contributions put the deep chain inside a buildSet lambda.
            appendLine("  @Provides @IntoSet fun provideDeep(value: Node0000): Any = value")
            appendLine("  @Provides @IntoSet fun provideOther(): Any = \"other\"")
            appendLine("  val root: Set<Any>")
          } else if (usesSuspendBindings) {
            appendLine("  @Provides suspend fun provideValue(): String = \"value\"")
            appendLine("  suspend fun root(): Node0000")
          } else {
            appendLine("  val root: Node0000")
          }
          appendLine("}")
          appendLine()
          appendLine("fun box(): String {")

          // Read acyclic roots to check that the generated code still works.
          // Do not read cyclic roots because they can still overflow at runtime.
          appendLine("  val graph = createGraph<StressGraph>()")
          if (!closesCycleWithProvider) {
            if (usesMultibindingRoot) {
              appendLine("  check(graph.root.size == 2)")
            } else if (usesSuspendBindings) {
              appendLine("  runBlocking { graph.root() }")
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
