// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test

/** Verifies that overlapping dependency cycles defer only the bindings they need. */
class DeferredBindingMinimizationTest : DynamicJvmBoxTest() {

  @Test
  fun overlappingCyclesOnlyDeferNecessaryBindings() {
    runOverlappingCycleTest(testName = "OverlappingCycleGraph")
  }

  @Test
  fun overlappingCyclesWithSwitchingProvidersOnlyDeferNecessaryBindings() {
    runOverlappingCycleTest(
      testName = "OverlappingCycleWithSwitchingProviders",
      enableSwitchingProviders = true,
    )
  }

  @Test
  fun necessaryCandidatesAreRestoredWhileRedundantCandidatesAreRemoved() {
    runDynamicBoxTest("RestoredCandidateGraph") {
      // Removing D would leave its cycle with C intact, so D must be restored.
      appendLine("@Inject class A(val b: Lazy<B>)")
      appendLine("@Inject class B(val a: A, val d: Lazy<D>)")
      appendLine("@Inject class C(val d: D, val a: Lazy<A>)")
      appendLine("@Inject class D(val c: Lazy<C>)")
      appendLine()
      appendLine("@DependencyGraph")
      appendLine("interface RestoredCandidateGraph {")
      appendLine("  val a: A")
      appendLine("}")
      appendLine()
      appendLine("fun box(): String {")
      appendLine("  val graph = createGraph<RestoredCandidateGraph>()")
      appendLine("  val first = graph.a")
      appendLine("  val second = first.b.value")
      appendLine("  assertNotSame(first, second.a)")
      appendLine("  assertNotSame(first, second.d.value.c.value.a.value)")
      appendLine("  return \"OK\"")
      appendLine("}")
      appendDelegateFactoryAssertion("RestoredCandidateGraph")
    }
  }

  @Test
  fun implicitFactoriesAndEarlierBindingsKeepDeferralPriority() {
    runDynamicBoxTest("PriorityCycleGraph") {
      // Every pair forms a soft cycle, so any two candidates can break all cycles.
      appendLine("@Inject class A(val b: Lazy<B>, val factory: Z.Factory)")
      appendLine("@Inject class B(val a: Lazy<A>, val factory: Z.Factory)")
      appendLine("@AssistedInject")
      appendLine("class Z(val a: A, val b: B, @Assisted val name: String) {")
      appendLine("  @AssistedFactory")
      appendLine("  interface Factory {")
      appendLine("    fun create(name: String): Z")
      appendLine("  }")
      appendLine("}")
      appendLine()
      appendLine("@DependencyGraph")
      appendLine("interface PriorityCycleGraph {")
      appendLine("  val aProvider: Provider<A>")
      appendLine("  val bProvider: Provider<B>")
      appendLine("  val factoryProvider: Provider<Z.Factory>")
      appendLine("}")
      appendLine()
      appendLine("fun box(): String {")
      appendLine("  val graph = createGraph<PriorityCycleGraph>()")
      appendLine(
        "  assertIs<dev.zacsweers.metro.internal.DelegateFactory<*>>(graph.factoryProvider)"
      )
      appendLine("  assertIs<dev.zacsweers.metro.internal.DelegateFactory<*>>(graph.aProvider)")
      appendLine(
        "  assertFalse(graph.bProvider is dev.zacsweers.metro.internal.DelegateFactory<*>)"
      )
      appendLine("  val created = graph.factoryProvider().create(\"priority\")")
      appendLine("  assertEquals(\"priority\", created.name)")
      appendLine("  return \"OK\"")
      appendLine("}")
      appendDelegateFactoryAssertion("PriorityCycleGraph")
    }
  }

  private fun runOverlappingCycleTest(testName: String, enableSwitchingProviders: Boolean = false) {
    runDynamicBoxTest(testName) {
      if (enableSwitchingProviders) {
        appendLine("// ENABLE_SWITCHING_PROVIDERS: true")
      }

      // Each overlapping cycle needs a different deferred edge. The third edge is redundant.
      appendLine("@Inject class A(val b: Lazy<B>)")
      appendLine("@Inject class B(val a: A, val c: Lazy<C>)")
      appendLine("@Inject class C(val b: B, val a: Lazy<A>)")
      appendLine()
      appendLine("@DependencyGraph")
      appendLine("interface OverlappingCycleGraph {")
      appendLine("  val a: A")
      appendLine("}")
      appendLine()
      appendLine("fun box(): String {")
      appendLine("  val graph = createGraph<OverlappingCycleGraph>()")
      appendLine("  val first = graph.a")
      appendLine("  val second = first.b.value")
      appendLine("  assertNotSame(first, second.a)")
      appendLine("  assertNotSame(first, second.c.value.b.a)")
      appendLine("  return \"OK\"")
      appendLine("}")
      appendDelegateFactoryAssertion("OverlappingCycleGraph")
    }
  }

  /** Checks deferred factories on the graph itself, not on generated declaration factories. */
  private fun StringBuilder.appendDelegateFactoryAssertion(graphName: String) {
    appendLine()
    appendLine("// CHECK_BYTECODE_TEXT")
    appendLine("// @$graphName\$Impl.class:")
    appendLine("// 2 NEW dev/zacsweers/metro/internal/DelegateFactory")
  }
}
