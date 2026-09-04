// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.ScatterMap
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.tracing.TraceScope
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class BindingGraphTest : TraceScope by TraceScope.noop() {

  @Test
  fun `required graph root replaces an optional root for the same key`() {
    val typeKey = StringTypeKey("Missing")
    val optional = StringContextualTypeKey.create(typeKey, hasDefault = true)
    val required = StringContextualTypeKey.create(typeKey)
    val optionalEntry = StringBindingStack.Entry(optional, usage = "optional")
    val requiredEntry = StringBindingStack.Entry(required, usage = "required")
    val roots = linkedMapOf<StringContextualTypeKey, StringBindingStack.Entry>()

    roots.putGraphRoot(optional, optionalEntry)
    roots.putGraphRoot(required, requiredEntry)

    assertThat(roots.keys.single()).isSameInstanceAs(required)
    assertThat(roots.values.single()).isSameInstanceAs(requiredEntry)
  }

  @Test
  fun `optional graph root does not replace a required root for the same key`() {
    val typeKey = StringTypeKey("Missing")
    val required = StringContextualTypeKey.create(typeKey)
    val optional = StringContextualTypeKey.create(typeKey, hasDefault = true)
    val requiredEntry = StringBindingStack.Entry(required, usage = "required")
    val optionalEntry = StringBindingStack.Entry(optional, usage = "optional")
    val roots = linkedMapOf<StringContextualTypeKey, StringBindingStack.Entry>()

    roots.putGraphRoot(required, requiredEntry)
    roots.putGraphRoot(optional, optionalEntry)

    assertThat(roots.keys.single()).isSameInstanceAs(required)
    assertThat(roots.values.single()).isSameInstanceAs(requiredEntry)
  }

  @Test
  fun `graph roots with matching optionality keep the latest source`() {
    val typeKey = StringTypeKey("Missing")
    val first = StringContextualTypeKey.create(typeKey)
    val second = StringContextualTypeKey.create(typeKey)
    val firstEntry = StringBindingStack.Entry(first, usage = "first")
    val secondEntry = StringBindingStack.Entry(second, usage = "second")
    val roots = linkedMapOf<StringContextualTypeKey, StringBindingStack.Entry>()

    roots.putGraphRoot(first, firstEntry)
    roots.putGraphRoot(second, secondEntry)

    assertThat(roots.keys.single()).isSameInstanceAs(first)
    assertThat(roots.values.single()).isSameInstanceAs(secondEntry)
  }

  @Test
  fun `distinct graph roots keep their own sources`() {
    val first = StringContextualTypeKey.create(StringTypeKey("First"))
    val second = StringContextualTypeKey.create(StringTypeKey("Second"))
    val firstEntry = StringBindingStack.Entry(first)
    val secondEntry = StringBindingStack.Entry(second)
    val roots = linkedMapOf<StringContextualTypeKey, StringBindingStack.Entry>()

    roots.putGraphRoot(first, firstEntry)
    roots.putGraphRoot(second, secondEntry)

    assertThat(roots).containsExactly(first, firstEntry, second, secondEntry).inOrder()
  }

  @Test
  fun `new graph roots use one map write without a lookup`() {
    val key = StringContextualTypeKey.create(StringTypeKey("Root"))
    val entry = StringBindingStack.Entry(key)
    var writes = 0
    var reads = 0
    val roots =
      object : LinkedHashMap<StringContextualTypeKey, StringBindingStack.Entry>() {
        override fun put(
          key: StringContextualTypeKey,
          value: StringBindingStack.Entry,
        ): StringBindingStack.Entry? {
          writes++
          return super.put(key, value)
        }

        override fun get(key: StringContextualTypeKey): StringBindingStack.Entry? {
          reads++
          return super.get(key)
        }
      }

    roots.putGraphRoot(key, entry)

    assertThat(writes).isEqualTo(1)
    assertThat(reads).isEqualTo(0)
  }

  @Test
  fun `existing bindings notify root and shared dependency requests in either order`() {
    val first = "First".contextualTypeKey
    val second = "Second".contextualTypeKey
    val sharedRequest = "Shared".contextualTypeKey
    val sharedBinding = sharedRequest.typeKey.toBinding()
    val providers =
      listOf(first, second).associate { it.typeKey to it.typeKey.toBinding(sharedRequest) }

    for (providerOrder in listOf(listOf(first, second), listOf(second, first))) {
      val rootKey = "Root".contextualTypeKey
      val rootEntry = StringBindingStack.Entry(rootKey, usage = "root")
      // Root dependency order controls when the computed providers enter the traversal queue.
      val rootBinding = rootKey.typeKey.toBinding(providerOrder)
      val computed = mutableListOf<StringContextualTypeKey>()
      val createdEntries = mutableListOf<StringBindingStack.Entry>()
      val observed =
        mutableListOf<Triple<StringContextualTypeKey, StringBinding, StringBindingStack.Entry>>()
      val graph =
        MutableBindingGraph<
          String,
          StringTypeKey,
          StringContextualTypeKey,
          StringBinding,
          StringBindingStack.Entry,
          StringBindingStack,
        >(
          newBindingStack = { StringBindingStack("AppGraph") },
          newBindingStackEntry = { contextKey, callingBinding, _ ->
            StringBindingStack.Entry(contextKey, usage = callingBinding?.typeKey?.type).also {
              createdEntries += it
            }
          },
          computeBindings = { contextKey, _, _ ->
            computed += contextKey
            setOf(providers.getValue(contextKey.typeKey))
          },
          onExistingBinding = { contextKey, binding, entry ->
            observed += Triple(contextKey, binding, entry)
          },
        )
      graph.tryPut(rootBinding, StringBindingStack("AppGraph"))
      graph.tryPut(sharedBinding, StringBindingStack("AppGraph"))

      graph.seal(roots = mapOf(rootKey to rootEntry))

      assertThat(computed).containsExactlyElementsIn(providerOrder).inOrder()
      assertThat(observed).hasSize(3)
      val root = observed.first()
      assertThat(root.first).isSameInstanceAs(rootKey)
      assertThat(root.second).isSameInstanceAs(rootBinding)
      assertThat(root.third).isSameInstanceAs(rootEntry)
      val dependencies = observed.drop(1)
      assertThat(dependencies.map { it.third.usage })
        .containsExactlyElementsIn(providerOrder.map { it.typeKey.type })
        .inOrder()
      for ((request, binding, entry) in dependencies) {
        assertThat(request).isSameInstanceAs(sharedRequest)
        assertThat(binding).isSameInstanceAs(sharedBinding)
        assertThat(entry.contextKey).isSameInstanceAs(request)
        assertThat(createdEntries).contains(entry)
      }
      assertThat(createdEntries).hasSize(4)
    }
  }

  @Test
  fun `existing bindings need no lookups or stack entries when observation is disabled`() {
    val sharedRequest = "Shared".contextualTypeKey
    val rootKey = "Root".contextualTypeKey
    val rootEntry = StringBindingStack.Entry(rootKey)
    var computed = 0
    var createdEntries = 0
    val graph =
      MutableBindingGraph<
        String,
        StringTypeKey,
        StringContextualTypeKey,
        StringBinding,
        StringBindingStack.Entry,
        StringBindingStack,
      >(
        newBindingStack = { StringBindingStack("AppGraph") },
        newBindingStackEntry = { contextKey, _, _ ->
          createdEntries++
          StringBindingStack.Entry(contextKey)
        },
        computeBindings = { _, _, _ ->
          computed++
          emptySet()
        },
      )
    graph.tryPut(rootKey.typeKey.toBinding(sharedRequest), StringBindingStack("AppGraph"))
    graph.tryPut(sharedRequest.typeKey.toBinding(), StringBindingStack("AppGraph"))

    graph.seal(roots = mapOf(rootKey to rootEntry))

    assertThat(computed).isEqualTo(0)
    assertThat(createdEntries).isEqualTo(0)
  }

  @Test
  fun put() {
    val key = "key".typeKey
    val (graph) = buildGraph { binding("key") }

    assertTrue(key in graph)
  }

  @Test
  fun `put throws if graph is sealed`() {
    val (graph) = buildGraph { binding("key") }

    val exception =
      assertFailsWith<IllegalStateException> { graph.tryPut("key".typeKey.toBinding()) }
    assertThat(exception).hasMessageThat().contains("Graph already sealed")
  }

  @Test
  fun `seal processes dependencies and marks graph as sealed`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val (graph) = buildGraph { a dependsOn b }

    with(graph) {
      assertThat(a.dependsOn(b)).isTrue()
      assertThat(graph.sealed).isTrue()
    }
  }

  @Test
  fun `seal checks for cancellation`() {
    val graph = newStringBindingGraph()
    repeat(300) { index -> graph.tryPut("Binding$index".typeKey.toBinding()) }
    var checks = 0

    assertFailsWith<BindingGraphCancellationException> {
      graph.seal(
        shrinkUnusedBindings = false,
        ensureActive = {
          if (++checks == 2) throw BindingGraphCancellationException()
        },
      )
    }
    assertThat(checks).isEqualTo(2)
  }

  @Test
  fun `TypeKey dependsOn withDeferrableTypes`() {
    val a = "A".typeKey
    val b = "B".typeKey

    val (graph, result) =
      buildGraph {
        a dependsOn "() -> A".contextualTypeKey
        b dependsOn "Lazy<B>".contextualTypeKey
      }

    with(graph) {
      assertThat(a.dependsOn(a)).isTrue()
      assertThat(b.dependsOn(b)).isTrue()
    }

    assertThat(result.deferredTypes).containsExactly(a, b)
  }

  @Test
  fun `seal deferrableTypeDependencyGraph`() {
    val aProvider = "() -> A".typeKey
    val b = "B".typeKey

    val (graph, result) = buildGraph { aProvider dependsOn b }

    with(graph) { assertThat(aProvider.dependsOn(b)).isTrue() }

    assertThat(result.deferredTypes).isEmpty()
  }

  @Test
  fun `seal throws for strict dependency cycle`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val aBinding = a.toBinding(b)
    val bBinding = b.toBinding(a)
    val bindingGraph = newStringBindingGraph()

    bindingGraph.tryPut(aBinding)
    bindingGraph.tryPut(bBinding)

    val exception =
      assertFailsWith<IllegalStateException> {
        val _ = bindingGraph.seal(shrinkUnusedBindings = false)
      }
    assertThat(exception)
      .hasMessageThat()
      .contains(
        """
        [Metro/DependencyCycle] Found a dependency cycle while processing AppGraph

          cycle:
              +-> B -> A --+
              +------------+

          trace (in AppGraph):
              B
              A
              B
              ...

          help: you can break the cycle by injecting a deferred type at one edge, e.g. `() -> B` or
                `Lazy<B>`. Only do this if you know what you're doing though!
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#dependencycycle
        """
          .trimIndent()
      )
  }

  @Test
  fun `seal ignores soft cycles and reports only the hard cycle within a complex SCC`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val c = "C".typeKey
    val d = "D".typeKey

    // SCC: {A, B, C, D}
    // Legal cycle: A -> B -> (() -> A)
    // Illegal cycle : D -> B -> C -> D

    val aBinding = a.toBinding(b.contextualTypeKey)
    val bBinding = b.toBinding("() -> A".contextualTypeKey, c.contextualTypeKey)
    val cBinding = c.toBinding(d.contextualTypeKey)
    val dBinding = d.toBinding(b.contextualTypeKey)

    val bindingGraph = newStringBindingGraph()
    bindingGraph.tryPut(aBinding)
    bindingGraph.tryPut(bBinding)
    bindingGraph.tryPut(cBinding)
    bindingGraph.tryPut(dBinding)

    val exception =
      assertFailsWith<IllegalStateException> {
        val _ = bindingGraph.seal(shrinkUnusedBindings = false)
      }

    val message = exception.message!!

    val cycleLine = message.lines().find { it.contains("+->") }?.trim() ?: ""

    // Must contain B, C, and D, not A
    assertThat(cycleLine).contains("B")
    assertThat(cycleLine).contains("C")
    assertThat(cycleLine).contains("D")
    assertThat(cycleLine).doesNotContain("A")

    // Verify Trace
    val traceSection = message.substringAfter("trace (in AppGraph):").substringBefore("help:")
    assertThat(traceSection).doesNotContain("A")
    assertThat(traceSection).contains("B")
    assertThat(traceSection).contains("C")
    assertThat(traceSection).contains("D")
  }

  @Test
  fun `TypeKey dependsOn returns true for dependent keys`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val aBinding = a.toBinding(b)
    val bBinding = b.toBinding()
    val bindingGraph = newStringBindingGraph()

    bindingGraph.tryPut(aBinding)
    bindingGraph.tryPut(bBinding)
    val _ = bindingGraph.seal(shrinkUnusedBindings = false)

    with(bindingGraph) {
      assertThat(a.dependsOn(b)).isTrue()
      assertThat(b.dependsOn(a)).isFalse()
    }
  }

  @Test
  fun `TypeKey dependsOn handles transitive dependencies`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val c = "C".typeKey
    val aBinding = a.toBinding(b)
    val bBinding = b.toBinding(c)
    val bindingC = c.toBinding()
    val bindingGraph = newStringBindingGraph()

    bindingGraph.tryPut(aBinding)
    bindingGraph.tryPut(bBinding)
    bindingGraph.tryPut(bindingC)
    val _ = bindingGraph.seal(shrinkUnusedBindings = false)

    with(bindingGraph) {
      // Direct dependency
      assertThat(a.dependsOn(b)).isTrue()
      // Transitive dependency
      assertThat(a.dependsOn(c)).isTrue()
      // No dependency in the reverse direction
      assertThat(c.dependsOn(a)).isFalse()
    }
  }

  @Test
  fun `medium length traversal`() {
    // Create a chain
    val (graph) = buildChainedGraph("A", "B", "C", "D", "E")

    with(graph) {
      // Verify direct dependencies
      assertThat("A".typeKey.dependsOn("B".typeKey)).isTrue()
      assertThat("B".typeKey.dependsOn("C".typeKey)).isTrue()
      assertThat("C".typeKey.dependsOn("D".typeKey)).isTrue()
      assertThat("D".typeKey.dependsOn("E".typeKey)).isTrue()

      // Verify transitive dependencies
      assertThat("A".typeKey.dependsOn("E".typeKey)).isTrue()
      assertThat("B".typeKey.dependsOn("E".typeKey)).isTrue()

      // Verify no reverse dependencies
      assertThat("E".typeKey.dependsOn("A".typeKey)).isFalse()
    }
  }

  @Test
  fun `seal handles constructor injected types with dependencies`() {
    val c = "C".typeKey
    val d = "D".typeKey
    val e = "E".typeKey
    val a = "A".typeKey
    val dBinding = d.toBinding()
    val eBinding = e.toBinding(d)
    val cBinding = c.toBinding(e)

    val (graph) =
      buildGraph {
        constructorInjected(dBinding)
        constructorInjected(eBinding)
        constructorInjected(cBinding)
        a dependsOn c
        c dependsOn e
        e dependsOn d
      }

    with(graph) {
      assertThat(c.dependsOn(d)).isTrue()
      assertThat(c.dependsOn(e)).isTrue()
      assertThat(contains(c)).isTrue()
      assertThat(contains(d)).isTrue()
      assertThat(contains(e)).isTrue()
    }
  }

  @Test
  fun `short traversal with 3 nodes`() {
    // Create a short chain A1 -> A2 -> A3
    val (graph) =
      buildGraph {
        "A1" dependsOn "A2"
        "A2" dependsOn "A3"
      }

    // Verify that A1 depends on A3 transitively
    with(graph) {
      assertThat("A1".typeKey.dependsOn("A3".typeKey)).isTrue()
      assertThat("A3".typeKey.dependsOn("A1".typeKey)).isFalse()
    }
  }

  @Test
  fun `simple self cycle with Provider type`() {
    // A -> (() -> A)
    val (graph, result) =
      buildGraph {
        // Create a direct cycle
        "A".dependsOn("() -> A")
      }

    with(graph) { assertThat("A".typeKey.dependsOn("() -> A".typeKey)).isTrue() }
    assertThat(result.deferredTypes).containsExactly("A".typeKey)
  }

  @Test
  fun `mix of computed and non-computed bindings`() {
    // Create a graph with both computed and non-computed bindings
    val computedTypes = setOf("Computed1", "Computed2", "Computed3")

    val (graph) =
      buildGraph {
        // Add some regular bindings
        val a = binding("A")
        val b = binding("B")
        val c = binding("C")

        // Create dependencies on computed bindings
        a dependsOn "Computed1"
        b dependsOn "Computed2"
        c dependsOn "Computed3"

        // Create dependencies between computed bindings
        "Computed1".typeKey dependsOn "Computed2"
        "Computed2".typeKey dependsOn "Computed3"
      }

    // Verify that all bindings are in the graph
    with(graph) {
      assertThat(contains("A".typeKey)).isTrue()
      assertThat(contains("B".typeKey)).isTrue()
      assertThat(contains("C".typeKey)).isTrue()

      // Verify computed bindings are in the graph
      for (type in computedTypes) {
        assertThat(contains(type.typeKey)).isTrue()
      }

      // Verify dependencies
      assertThat("A".typeKey.dependsOn("Computed1".typeKey)).isTrue()
      assertThat("B".typeKey.dependsOn("Computed2".typeKey)).isTrue()
      assertThat("C".typeKey.dependsOn("Computed3".typeKey)).isTrue()

      // Verify dependencies between computed bindings
      assertThat("Computed1".typeKey.dependsOn("Computed2".typeKey)).isTrue()
      assertThat("Computed2".typeKey.dependsOn("Computed3".typeKey)).isTrue()

      // Verify transitive dependencies
      assertThat("A".typeKey.dependsOn("Computed3".typeKey)).isTrue()
      assertThat("Computed1".typeKey.dependsOn("Computed3".typeKey)).isTrue()
    }
  }

  @Test
  fun `direct cycle with lazy`() {
    // A -> Lazy<A>
    val (graph, result) = buildGraph { "A" dependsOn "Lazy<A>" }

    with(graph) { assertThat("A".typeKey.dependsOn("Lazy<A>".typeKey)).isTrue() }
    assertThat(result.deferredTypes).containsExactly("A".typeKey)
  }

  @Test
  fun `duplicate bindings are an error - same key - equal bindings`() {
    val throwable = assertFails {
      buildGraph {
        binding("A")
        binding("A")
      }
    }
    assertThat(throwable)
      .hasMessageThat()
      .contains(
        """
        [Metro/DuplicateBinding] Multiple bindings found for A

              A
              A

          note: the duplicate bindings are all equal
          help: remove or disambiguate the duplicate bindings (e.g. with distinct qualifiers), or use
                @IntoSet/@IntoMap if you intended a multibinding
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#duplicatebinding
        """
          .trimIndent()
      )
  }

  @Test
  fun `duplicate bindings are an error - same key - same bindings`() {
    val aBinding = "A".typeKey.toBinding()
    val throwable = assertFails {
      buildGraph {
        tryPut(aBinding)
        tryPut(aBinding)
      }
    }
    assertThat(throwable)
      .hasMessageThat()
      .contains(
        """
        [Metro/DuplicateBinding] Multiple bindings found for A

              A
              A

          note: the duplicate bindings are all the same instance
          help: remove or disambiguate the duplicate bindings (e.g. with distinct qualifiers), or use
                @IntoSet/@IntoMap if you intended a multibinding
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#duplicatebinding
        """
          .trimIndent()
      )
  }
}

private class BindingGraphCancellationException : RuntimeException()

private val String.typeKey: StringTypeKey
  get() = contextualTypeKey.typeKey

private val String.contextualTypeKey: StringContextualTypeKey
  get() = StringTypeKey(this).contextualTypeKey

private val StringTypeKey.contextualTypeKey: StringContextualTypeKey
  get() = StringContextualTypeKey.create(this)

private fun StringTypeKey.toBinding(
  dependencies: List<StringContextualTypeKey> = emptyList()
): StringBinding {
  return StringBinding(this, dependencies)
}

private fun StringTypeKey.toBinding(vararg dependencies: StringContextualTypeKey): StringBinding {
  return toBinding(dependencies.toList())
}

private fun StringTypeKey.toBinding(vararg dependencies: StringTypeKey): StringBinding {
  return toBinding(dependencies.map { it.contextualTypeKey })
}

private fun newStringBindingGraph(
  graph: String = "AppGraph",
  computeBinding:
    (StringContextualTypeKey, ScatterMap<StringTypeKey, *>, StringBindingStack) -> Set<
        StringBinding
      > =
    { _, _, _ ->
      emptySet()
    },
): StringGraph {
  return StringGraph(
    newBindingStack = { StringBindingStack(graph) },
    newBindingStackEntry = { contextKey, _, _ -> StringBindingStack.Entry(contextKey) },
    computeBinding = computeBinding,
  )
}

@IgnorableReturnValue
context(traceScope: TraceScope)
private fun buildGraph(
  body: StringGraphBuilder.() -> Unit
): Pair<StringGraph, GraphTopology<StringTypeKey>> {
  return StringGraphBuilder().apply(body).sealAndReturn()
}

// Helper method to create a graph with a chain of dependencies
context(traceScope: TraceScope)
private fun buildChainedGraph(
  vararg nodes: String
): Pair<StringGraph, GraphTopology<StringTypeKey>> {
  return buildGraph {
    for (i in 0 until nodes.size - 1) {
      nodes[i] dependsOn nodes[i + 1]
    }
  }
}

internal class StringGraphBuilder {
  private val constructorInjectedTypes = mutableMapOf<StringTypeKey, StringBinding>()
  private val graph = newStringBindingGraph { contextKey, _, _ ->
    setOfNotNull(constructorInjectedTypes[contextKey.typeKey])
  }

  @IgnorableReturnValue
  fun binding(key: String): String {
    binding(key.contextualTypeKey)
    return key
  }

  @IgnorableReturnValue
  fun binding(contextKey: StringContextualTypeKey): StringContextualTypeKey {
    tryPut(contextKey.typeKey.toBinding())
    return contextKey
  }

  fun tryPut(binding: StringBinding) {
    graph.tryPut(binding)
  }

  @IgnorableReturnValue
  infix fun String.dependsOn(other: String): String {
    typeKey.dependsOn(other.contextualTypeKey)
    return other
  }

  @IgnorableReturnValue
  infix fun StringTypeKey.dependsOn(other: String): String {
    dependsOn(other.contextualTypeKey)
    return other
  }

  @IgnorableReturnValue
  infix fun StringTypeKey.dependsOn(other: StringTypeKey): StringTypeKey {
    dependsOn(other.contextualTypeKey)
    return other
  }

  @IgnorableReturnValue
  infix fun StringTypeKey.dependsOn(other: StringContextualTypeKey): StringContextualTypeKey {
    val currentDeps = graph[this]?.dependencies.orEmpty()
    val newBinding = StringBinding(this, currentDeps + other)
    graph.replace(newBinding)
    if (other.typeKey !in graph && other.typeKey !in constructorInjectedTypes) {
      graph.tryPut(other.typeKey.toBinding())
    }
    return other
  }

  @IgnorableReturnValue
  infix fun StringBinding.dependsOn(other: StringContextualTypeKey): StringContextualTypeKey {
    val currentDeps = dependencies
    graph.tryPut(typeKey.toBinding(currentDeps + other))
    if (other.typeKey !in graph) {
      graph.tryPut(other.typeKey.toBinding())
    }
    return other
  }

  fun constructorInjected(key: StringTypeKey) {
    constructorInjected(key.toBinding())
  }

  fun constructorInjected(binding: StringBinding) {
    constructorInjectedTypes[binding.typeKey] = binding
  }

  context(traceScope: TraceScope)
  fun sealAndReturn(): Pair<StringGraph, GraphTopology<StringTypeKey>> {
    return graph to graph.seal(shrinkUnusedBindings = false)
  }
}
