// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test

/**
 * Tests deep dependency graphs without adding thousands of fixture classes.
 *
 * The `StressTest` suffix keeps these tests behind `-Pmetro.enableLargeTests`.
 */
class GraphExpressionGeneratorStressTest : DynamicJvmBoxTest() {

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
    runDynamicBoxTest(testName) {
      if (usesSuspendBindings) {
        appendLine("// ENABLE_SUSPEND_PROVIDERS")
      }

      val rootDependencies = buildList {
        if (hasBranchedRoot) {
          // Add another dependency to make sure the deep branch is still checked.
          add("extra: Extra")
        }
        if (synchronousBranchBindingCount > 0) {
          add("synchronousDependency: SynchronousNode0000")
          add("suspendValue: String")
        }
      }
      val leafDependency =
        when {
          closesCycleWithProvider -> "() -> Node0000"
          usesSuspendBindings -> "String"
          else -> null
        }

      // Keep bindings unscoped so graph fields do not shorten the generated call chain.
      appendInjectedBindingChain(
        bindingCount = bindingCount,
        leafDependency = leafDependency,
        rootDependencies = rootDependencies,
      )

      if (synchronousBranchBindingCount > 0) {
        appendLine()
        // This branch starts as scalar but becomes a provider dependency of a suspend factory.
        appendInjectedBindingChain(
          bindingCount = synchronousBranchBindingCount,
          namePrefix = "SynchronousNode",
        )
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
  }
}
