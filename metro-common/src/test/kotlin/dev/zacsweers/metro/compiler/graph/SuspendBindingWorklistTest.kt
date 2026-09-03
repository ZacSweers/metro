// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlin.test.assertFailsWith
import org.junit.Test

class SuspendBindingWorklistTest {

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
  fun `path analysis returns a stable snapshot`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("Consumer", "Source"))
    val initial = fixture.analysis.analyzeWithPaths(keys("Consumer"))

    fixture.put(binding("Source", isSuspend = true))
    assertThat(fixture.analysis.isSuspend(key("Consumer"))).isTrue()

    assertThat(initial.suspendKeys).isEmpty()
    assertThat(initial.pathFrom(key("Consumer")) { it.typeKey }).isNull()
  }

  @Test
  fun `nonempty path snapshots stay stable after incremental additions`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("First", "FirstSource"), binding("FirstSource", isSuspend = true))
    val initial = fixture.analysis.analyzeWithPaths(keys("First"))

    fixture.put(binding("Second", "SecondSource"), binding("SecondSource", isSuspend = true))
    assertThat(fixture.analysis.analyze(keys("First", "Second")))
      .containsExactlyElementsIn(keys("First", "FirstSource", "Second", "SecondSource"))

    assertThat(initial.suspendKeys).containsExactlyElementsIn(keys("First", "FirstSource"))
    assertThat(initial.pathFrom(key("Second")) { it.typeKey }).isNull()
    assertThat(initial.pathFrom(key("First")) { it.typeKey }!!.sourceKey)
      .isEqualTo(key("FirstSource"))
  }

  @Test
  fun `older snapshots keep their witness when a preferred source appears later`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("Consumer", "Preferred", "Existing"), binding("Existing", isSuspend = true))
    val initial = fixture.analysis.analyzeWithPaths(keys("Consumer"))

    fixture.put(binding("Preferred", isSuspend = true))
    val updated = fixture.analysis.analyzeWithPaths(keys("Consumer"))

    assertThat(initial.pathFrom(key("Consumer")) { it.typeKey }!!.sourceKey)
      .isEqualTo(key("Existing"))
    assertThat(updated.pathFrom(key("Consumer")) { it.typeKey }!!.sourceKey)
      .isEqualTo(key("Preferred"))
  }

  @Test
  fun `multiple witnesses reuse one provenance index`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("First", "Middle"),
      binding("Second", "Middle"),
      binding("Middle", "Source"),
      binding("Source", isSuspend = true),
    )
    val result = fixture.analysis.analyzeWithPaths(keys("First", "Second"))

    assertThat(result.pathFrom(key("First")) { it.typeKey }!!.sourceKey).isEqualTo(key("Source"))
    val checksAfterFirstWitness = fixture.suspendCheckCount
    assertThat(result.pathFrom(key("Second")) { it.typeKey }!!.sourceKey).isEqualTo(key("Source"))

    // The second witness checks its own start and final source without indexing the graph again.
    assertThat(fixture.suspendCheckCount - checksAfterFirstWitness).isEqualTo(2)
  }

  @Test
  fun `witness indexing observes cancellation`() {
    var canceled = false
    val fixture = AnalysisFixture(checkCanceled = { if (canceled) error("canceled") })
    fixture.put(binding("Consumer", "Source"), binding("Source", isSuspend = true))
    val result = fixture.analysis.analyzeWithPaths(keys("Consumer"))
    canceled = true

    val failure =
      assertFailsWith<IllegalStateException> {
        result.pathFrom(key("Consumer")) { it.typeKey }
      }

    assertThat(failure).hasMessageThat().isEqualTo("canceled")
  }

  @Test
  fun `root collection observes cancellation`() {
    var rootsVisited = 0
    val fixture =
      AnalysisFixture(
        checkCanceled = {
          if (rootsVisited == 8) throw AnalysisCancellationException()
        }
      )
    val roots = sequence {
      repeat(300) { index ->
        rootsVisited++
        yield(key("Root$index"))
      }
    }
      .asIterable()

    assertFailsWith<AnalysisCancellationException> { fixture.analysis.analyze(roots) }

    assertThat(rootsVisited).isEqualTo(8)
    assertThat(fixture.lookupCount("Root0")).isEqualTo(0)
  }

  @Test
  fun `nonsuspend dependency discovery observes cancellation`() {
    lateinit var fixture: AnalysisFixture
    fixture =
      AnalysisFixture(
        checkCanceled = {
          if (fixture.lookupCount("Node8") > 0) throw AnalysisCancellationException()
        }
      )
    fixture.put(*List(300) { index -> binding("Node$index", "Node${index + 1}") }.toTypedArray())

    assertFailsWith<AnalysisCancellationException> { fixture.analysis.analyze(keys("Node0")) }

    assertThat(fixture.lookupCount("Node8")).isEqualTo(1)
    assertThat(fixture.lookupCount("Node9")).isEqualTo(0)
  }

  @Test
  fun `large dependency lists observe cancellation`() {
    lateinit var fixture: AnalysisFixture
    fixture =
      AnalysisFixture(
        checkCanceled = {
          if (fixture.lookupCount("Dependency8") > 0) throw AnalysisCancellationException()
        }
      )
    val dependencies = List(300) { "Dependency$it" }
    fixture.put(binding("Root", *dependencies.toTypedArray()))
    fixture.put(*dependencies.map { binding(it) }.toTypedArray())

    assertFailsWith<AnalysisCancellationException> { fixture.analysis.analyze(keys("Root")) }

    assertThat(fixture.lookupCount("Dependency8")).isEqualTo(1)
    assertThat(fixture.lookupCount("Dependency9")).isEqualTo(0)
  }

  @Test
  fun `pending edge classification observes cancellation`() {
    lateinit var fixture: AnalysisFixture
    fixture =
      AnalysisFixture(
        checkCanceled = {
          if (fixture.passThroughCheckCount > 0) throw AnalysisCancellationException()
        }
      )
    val consumers = List(300) { binding("Consumer$it", "Missing") }
    fixture.put(*consumers.toTypedArray())
    assertThat(fixture.analysis.analyze(consumers.map { it.typeKey })).isEmpty()
    fixture.put(binding("Missing", isSuspend = true))

    assertFailsWith<AnalysisCancellationException> { fixture.analysis.analyze(keys("Missing")) }

    assertThat(fixture.passThroughCheckCount).isEqualTo(1)
  }

  @Test
  fun `propagation observes cancellation after discovery completes`() {
    var canceled = false
    val fixture =
      AnalysisFixture(
        checkCanceled = { if (canceled) throw AnalysisCancellationException() },
        onExpand = { if (it.typeKey == key("Source")) canceled = true },
      )
    fixture.put(
      binding("Root", "Middle"),
      binding("Middle", "Source"),
      binding("Source", isSuspend = true),
    )

    assertFailsWith<AnalysisCancellationException> { fixture.analysis.analyze(keys("Root")) }

    assertThat(fixture.lookupCount("Source")).isEqualTo(1)
  }

  @Test
  fun `cycle walks pick the adjacent suspend witness over the cycle edge`() {
    val fixture = AnalysisFixture()
    fixture.put(binding("A", "B"), binding("B", "A", "Source"), binding("Source", isSuspend = true))
    val result = fixture.analysis.analyzeWithPaths(keys("A"))
    assertThat(result.suspendKeys).containsExactlyElementsIn(keys("A", "B", "Source"))

    // B's first dependency leads back into the cycle, but Source is directly suspend and wins.
    val path = checkNotNull(result.pathFrom(key("A")) { it.typeKey })
    assertThat(path.sourceIsSuspend).isTrue()
    assertThat(path.sourceKey).isEqualTo(key("Source"))
    assertThat(path.edges.map { it.consumerKey }).containsExactly(key("A"), key("B")).inOrder()
  }

  @Test
  fun `cycle branches cannot hide a suspend witness on another branch`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("A", "B", "C"),
      binding("B", "A"),
      binding("C", "D"),
      binding("D", isSuspend = true),
    )
    val result = fixture.analysis.analyzeWithPaths(keys("A"))

    val path = checkNotNull(result.pathFrom(key("A")) { it.typeKey })

    assertThat(path.sourceIsSuspend).isTrue()
    assertThat(path.sourceKey).isEqualTo(key("D"))
    assertThat(path.edges.map { it.consumerKey }).containsExactly(key("A"), key("C")).inOrder()
  }

  @Test
  fun `suspend witness uses the shortest available dependency path`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("A", "Long", "Short"),
      binding("Long", "Middle"),
      binding("Middle", "FarSource"),
      binding("Short", "NearSource"),
      binding("FarSource", isSuspend = true),
      binding("NearSource", isSuspend = true),
    )
    val result = fixture.analysis.analyzeWithPaths(keys("A"))

    val path = checkNotNull(result.pathFrom(key("A")) { it.typeKey })

    assertThat(path.sourceIsSuspend).isTrue()
    assertThat(path.sourceKey).isEqualTo(key("NearSource"))
    assertThat(path.edges.map { it.consumerKey }).containsExactly(key("A"), key("Short")).inOrder()
  }

  @Test
  fun `equal direct witnesses follow declaration order regardless of source order`() {
    for (sourceOrder in listOf(listOf("First", "Second"), listOf("Second", "First"))) {
      val fixture = AnalysisFixture()
      for (source in sourceOrder) {
        fixture.put(binding(source, isSuspend = true))
      }
      fixture.put(binding("Consumer", "First", "Second"))

      val result = fixture.analysis.analyzeWithPaths(sourceOrder.map(::key) + key("Consumer"))
      val path = checkNotNull(result.pathFrom(key("Consumer")) { it.typeKey })

      assertThat(path.sourceKey).isEqualTo(key("First"))
      assertThat(path.edges.single().dependency.typeKey).isEqualTo(key("First"))
    }
  }

  @Test
  fun `equal nested witnesses preserve each root declaration order through cycles`() {
    val fixture = AnalysisFixture()
    fixture.put(
      binding("SecondSource", isSuspend = true),
      binding("FirstSource", isSuspend = true),
      binding("Cycle", "First"),
      binding("Second", "SecondSource"),
      binding("First", "Cycle", "FirstSource"),
      binding("FirstRoot", "First", "Second"),
      binding("SecondRoot", "Second", "First"),
    )
    val result =
      fixture.analysis.analyzeWithPaths(
        keys("SecondSource", "FirstSource", "SecondRoot", "FirstRoot")
      )

    val firstPath = checkNotNull(result.pathFrom(key("FirstRoot")) { it.typeKey })
    assertThat(firstPath.sourceKey).isEqualTo(key("FirstSource"))
    assertThat(firstPath.edges.map { it.consumerKey })
      .containsExactly(key("FirstRoot"), key("First"))
      .inOrder()

    val secondPath = checkNotNull(result.pathFrom(key("SecondRoot")) { it.typeKey })
    assertThat(secondPath.sourceKey).isEqualTo(key("SecondSource"))
    assertThat(secondPath.edges.map { it.consumerKey })
      .containsExactly(key("SecondRoot"), key("Second"))
      .inOrder()
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

private class AnalysisFixture(
  private val checkCanceled: () -> Unit = {},
  private val onExpand: (TestBinding) -> Unit = {},
) {
  private val bindings = mutableMapOf<StringTypeKey, TestBinding>()
  private val lookupCounts = mutableMapOf<StringTypeKey, Int>()
  private var generation = 0
  var suspendCheckCount = 0
    private set

  var passThroughCheckCount = 0
    private set

  private val rules =
    SuspendBindingRules<String, StringTypeKey, StringContextualTypeKey, TestBinding>(
      findBinding = bindings::get,
      bindingCanPassThrough = { binding, _ ->
        passThroughCheckCount++
        binding.passesThrough
      },
    )

  val analysis: TestAnalysis =
    SuspendBindingWorklist(
      findBinding = { key ->
        lookupCounts[key] = lookupCounts.getOrDefault(key, 0) + 1
        bindings[key]
      },
      bindingIsSuspend = {
        suspendCheckCount++
        it.isSuspend
      },
      skipDependencyTraversal = {
        onExpand(it)
        it.skipDependencies
      },
      rules = rules,
      currentGraphGeneration = { generation },
      checkCanceled = checkCanceled,
    )

  fun put(vararg newBindings: TestBinding) {
    for (binding in newBindings) {
      check(bindings.put(binding.typeKey, binding) == null)
      generation++
    }
  }

  fun lookupCount(name: String): Int = lookupCounts.getOrDefault(key(name), 0)
}

private class AnalysisCancellationException : RuntimeException()

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
