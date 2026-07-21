// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.graph.BaseBinding
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.compiler.graph.StringContextualTypeKey
import dev.zacsweers.metro.compiler.graph.StringTypeKey
import kotlin.random.Random
import org.junit.Test

class SuspendBindingAnalysisTest {

  @Test
  fun `same root is updated after a missing dependency is added`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("A", "B"))

    assertThat(fixture.analysis.isSuspend(key("A"))).isFalse()
    assertThat(fixture.lookupCount("A")).isEqualTo(1)
    assertThat(fixture.lookupCount("B")).isEqualTo(1)

    fixture.put(binding("B", isSuspend = true))

    assertThat(fixture.analysis.isSuspend(key("A"))).isTrue()
    assertThat(fixture.lookupCount("A")).isEqualTo(1)
    assertThat(fixture.lookupCount("B")).isEqualTo(2)

    assertThat(fixture.analysis.isSuspend(key("A"))).isTrue()
    assertThat(fixture.lookupCount("A")).isEqualTo(1)
    assertThat(fixture.lookupCount("B")).isEqualTo(2)
  }

  @Test
  fun `different root retries misses and propagates through expanded consumers`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("A", "B"),
      binding("C", "B"),
      binding("D", "A", "C"),
      binding("E"),
    )

    assertThat(fixture.analysis.isSuspend(key("D"))).isFalse()
    fixture.put(binding("B", isSuspend = true))

    assertThat(fixture.analysis.isSuspend(key("E"))).isFalse()
    assertThat(fixture.analysis.analyze(keys("A", "B", "C", "D")))
      .containsAtLeastElementsIn(keys("A", "B", "C", "D"))
    assertThat(fixture.lookupCount("B")).isEqualTo(2)
    for (name in listOf("A", "C", "D", "E")) {
      assertThat(fixture.lookupCount(name)).isEqualTo(1)
    }
  }

  @Test
  fun `a miss is looked up once per graph generation`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("A", "Missing"), binding("B", "Missing"), binding("C", "Missing"))

    assertThat(fixture.analysis.isSuspend(key("A"))).isFalse()
    assertThat(fixture.analysis.isSuspend(key("B"))).isFalse()
    assertThat(fixture.analysis.isSuspend(key("C"))).isFalse()
    assertThat(fixture.lookupCount("Missing")).isEqualTo(1)

    fixture.analysis.analyze(keys("A", "B", "C"))
    assertThat(fixture.lookupCount("Missing")).isEqualTo(1)

    fixture.put(binding("Unrelated"))
    fixture.analysis.isSuspend(key("Unrelated"))
    assertThat(fixture.lookupCount("Missing")).isEqualTo(2)

    fixture.put(binding("AnotherUnrelated"))
    fixture.analysis.isSuspend(key("A"))
    assertThat(fixture.lookupCount("Missing")).isEqualTo(3)
  }

  @Test
  fun `source first and consumer first reach the same result`() {
    val sourceFirst = AnalysisFixture()
    sourceFirst.put(binding("Source", isSuspend = true), binding("Consumer", "Source"))

    val consumerFirst = AnalysisFixture()
    consumerFirst.put(binding("Consumer", "Source"))
    assertThat(consumerFirst.analysis.isSuspend(key("Consumer"))).isFalse()
    consumerFirst.put(binding("Source", isSuspend = true))

    assertThat(sourceFirst.analysis.isSuspend(key("Consumer"))).isTrue()
    assertThat(consumerFirst.analysis.isSuspend(key("Consumer"))).isTrue()
    assertThat(sourceFirst.analysis.analyze(keys("Source", "Consumer")))
      .containsExactlyElementsIn(consumerFirst.analysis.analyze(keys("Source", "Consumer")))
  }

  @Test
  fun `propagation handles fanout diamonds and cycles`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("Root", "Left", "Right"),
      binding("OtherRoot", "Right"),
      binding("Left", "Leaf", "Cycle"),
      binding("Right", "Leaf"),
      binding("Cycle", "Left"),
      binding("Leaf", isSuspend = true),
    )

    val allKeys = keys("OtherRoot", "Root", "Cycle", "Right", "Left", "Leaf")
    assertThat(fixture.analysis.analyze(allKeys)).containsExactlyElementsIn(allKeys)
    for (key in allKeys) {
      assertThat(fixture.lookupCount(key.type)).isEqualTo(1)
    }
  }

  @Test
  fun `deferred edges stop propagation`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("Source", isSuspend = true),
      binding("Eager", "Source"),
      binding("Function", "() -> Source"),
      binding("Lazy", "Lazy<Source>"),
    )

    assertThat(fixture.analysis.analyze(keys("Eager", "Function", "Lazy", "Source")))
      .containsExactlyElementsIn(keys("Eager", "Source"))
  }

  @Test
  fun `pending edges are classified when a binding resolves`() {
    val passThrough = AnalysisFixture()
    passThrough.put(binding("Consumer", "Dependency"), binding("Source", isSuspend = true))
    assertThat(passThrough.analysis.isSuspend(key("Consumer"))).isFalse()
    passThrough.put(binding("Dependency", "Source", passesThrough = true))

    assertThat(passThrough.analysis.isSuspend(key("Consumer"))).isFalse()
    assertThat(passThrough.analysis.isSuspend(key("Dependency"))).isTrue()

    val propagating = AnalysisFixture()
    propagating.put(binding("Consumer", "Dependency"), binding("Source", isSuspend = true))
    assertThat(propagating.analysis.isSuspend(key("Consumer"))).isFalse()
    propagating.put(binding("Dependency", "Source"))

    assertThat(propagating.analysis.isSuspend(key("Consumer"))).isTrue()
  }

  @Test
  fun `skipped bindings do not traverse their dependencies`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("Factory", "Source", skipDependencies = true))

    assertThat(fixture.analysis.isSuspend(key("Factory"))).isFalse()
    assertThat(fixture.lookupCount("Source")).isEqualTo(0)

    fixture.put(binding("Source", isSuspend = true))
    assertThat(fixture.analysis.isSuspend(key("Factory"))).isFalse()
  }

  @Test
  fun `incremental results match a fixpoint oracle`() {
    val random = Random(8675309)
    val names = (0 until 24).map { "Node$it" }
    val allBindings = names.mapIndexed { index, name ->
      val dependencies = buildList {
        repeat(if (index == 0) 0 else 1 + random.nextInt(3)) {
          val target = names[random.nextInt(names.size)]
          add(if ((index + size) % 5 == 0) "() -> $target" else target)
        }
      }
      binding(
        name,
        *dependencies.toTypedArray(),
        isSuspend = index % 7 == 0,
        skipDependencies = index % 13 == 0,
        passesThrough = index % 11 == 0,
      )
    }
    val fixture = AnalysisFixture()
    val available = linkedMapOf<StringTypeKey, TestBinding>()

    for (batch in allBindings.chunked(4)) {
      fixture.put(*batch.toTypedArray())
      batch.associateByTo(available) { it.typeKey }

      val expected = fixpointSuspendKeys(available)
      val actual = fixture.analysis.analyze(names.map(::key))
      assertThat(actual).containsExactlyElementsIn(expected)
    }
  }
}

private typealias TestAnalysis =
  SuspendBindingWorklist<String, StringTypeKey, StringContextualTypeKey, TestBinding>

private class AnalysisFixture {
  private val bindings = mutableMapOf<StringTypeKey, TestBinding>()
  private val lookupCounts = mutableMapOf<StringTypeKey, Int>()
  private var generation = 0

  val analysis: TestAnalysis =
    SuspendBindingWorklist(
      findBinding = { key ->
        lookupCounts[key] = lookupCounts.getOrDefault(key, 0) + 1
        bindings[key]
      },
      bindingIsSuspend = { it.isSuspend },
      skipDependencyTraversal = { it.skipDependencies },
      canPassThrough = { binding, _ -> binding.passesThrough },
      currentGraphGeneration = { generation },
    )

  fun put(vararg newBindings: TestBinding) {
    for (binding in newBindings) {
      check(bindings.put(binding.typeKey, binding) == null)
      generation++
    }
  }

  fun lookupCount(name: String): Int = lookupCounts.getOrDefault(key(name), 0)
}

private class TestBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  override val dependencies: List<StringContextualTypeKey>,
  val isSuspend: Boolean,
  val skipDependencies: Boolean,
  val passesThrough: Boolean,
) : BaseBinding<String, StringTypeKey, StringContextualTypeKey> {
  override fun renderLocationDiagnostic(
    short: Boolean,
    shortLocation: Boolean,
    underlineTypeKey: Boolean,
  ): LocationDiagnostic = LocationDiagnostic(typeKey.type, null)

  override fun renderDescriptionDiagnostic(short: Boolean, underlineTypeKey: Boolean): String =
    typeKey.type
}

private fun binding(
  name: String,
  vararg dependencies: String,
  isSuspend: Boolean = false,
  skipDependencies: Boolean = false,
  passesThrough: Boolean = false,
): TestBinding =
  TestBinding(
    contextualTypeKey = contextKey(name),
    dependencies = dependencies.map(::contextKey),
    isSuspend = isSuspend,
    skipDependencies = skipDependencies,
    passesThrough = passesThrough,
  )

private fun fixpointSuspendKeys(bindings: Map<StringTypeKey, TestBinding>): Set<StringTypeKey> {
  val suspendKeys = bindings.values.filter { it.isSuspend }.mapTo(mutableSetOf()) { it.typeKey }
  var changed: Boolean
  do {
    changed = false
    for (binding in bindings.values) {
      if (binding.typeKey in suspendKeys || binding.skipDependencies) continue
      val requiresSuspend =
        binding.dependencies.any { dependency ->
          if (dependency.isDeferrable) return@any false
          val dependencyBinding = bindings[dependency.typeKey] ?: return@any false
          !dependencyBinding.passesThrough && dependency.typeKey in suspendKeys
        }
      if (requiresSuspend) {
        changed = suspendKeys.add(binding.typeKey) || changed
      }
    }
  } while (changed)
  return suspendKeys
}

private fun key(type: String): StringTypeKey = contextKey(type).typeKey

private fun keys(vararg types: String): List<StringTypeKey> = types.map(::key)

private fun contextKey(type: String): StringContextualTypeKey =
  StringContextualTypeKey.create(StringTypeKey(type))
