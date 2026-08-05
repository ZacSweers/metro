// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test

/** Verifies that depth-limiting getters preserve binding behavior. */
class BindingExpressionDepthLimiterTest : DynamicJvmBoxTest() {

  @Test
  fun scalarGettersPreserveUnscopedBindings() {
    runDepthLimitingTest(testName = "ScalarDepthLimitingGetter", providerRoot = false)
  }

  @Test
  fun providerGettersPreserveLazinessAndUnscopedBindings() {
    runDepthLimitingTest(testName = "ProviderDepthLimitingGetter", providerRoot = true)
  }

  @Test
  fun suspendGettersPreserveUnscopedBindings() {
    runDepthLimitingTest(
      testName = "SuspendDepthLimitingGetter",
      providerRoot = false,
      usesSuspendBindings = true,
    )
  }

  /** Runs a small generated graph through the full compiler and executes its box function. */
  private fun runDepthLimitingTest(
    testName: String,
    providerRoot: Boolean,
    usesSuspendBindings: Boolean = false,
  ) {
    runDynamicBoxTest(testName) {
      if (usesSuspendBindings) {
        appendLine("// ENABLE_SUSPEND_PROVIDERS")
      }
      appendLine("private var created = 0")
      appendLine()

      // This graph exceeds the inline depth limit without slowing normal tests.
      appendInjectedBindingChain(
        bindingCount = 80,
        leafDependency = if (usesSuspendBindings) "String" else null,
        rootBody = "{ init { created++ } }",
      )

      appendLine()
      appendLine("@DependencyGraph")
      appendLine("interface DepthLimitedGraph {")
      if (usesSuspendBindings) {
        appendLine("  @Provides suspend fun provideValue(): String = \"value\"")
        appendLine("  suspend fun root(): Node0000")
      } else if (providerRoot) {
        appendLine("  val root: () -> Node0000")
      } else {
        appendLine("  val root: Node0000")
      }
      appendLine("}")
      appendLine()
      appendLine("fun box(): String {")
      appendLine("  val graph = createGraph<DepthLimitedGraph>()")
      appendLine("  check(created == 0)")
      if (usesSuspendBindings) {
        appendLine("  val first = runBlocking { graph.root() }")
        appendLine("  check(created == 1)")
        appendLine("  val second = runBlocking { graph.root() }")
      } else if (providerRoot) {
        appendLine("  val provider = graph.root")
        appendLine("  check(created == 0)")
        appendLine("  val first = provider()")
        appendLine("  check(created == 1)")
        appendLine("  val second = provider()")
      } else {
        appendLine("  val first = graph.root")
        appendLine("  check(created == 1)")
        appendLine("  val second = graph.root")
      }
      appendLine("  check(created == 2)")
      appendLine("  check(first !== second)")
      appendLine("  return \"OK\"")
      appendLine("}")
    }
  }
}
