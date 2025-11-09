// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class BindingGraphTest {

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
  fun `TypeKey dependsOn withDeferrableTypes`() {
    val a = "A".typeKey
    val b = "B".typeKey

    val (graph, result) =
      buildGraph {
        a dependsOn "Provider<A>".contextualTypeKey
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
    val aProvider = "Provider<A>".typeKey
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
      assertFailsWith<IllegalStateException> { bindingGraph.seal(shrinkUnusedBindings = false) }
    assertThat(exception)
      .hasMessageThat()
      .contains(
        """
        [Metro/DependencyCycle] Found a dependency cycle while processing 'AppGraph'.
        Cycle:
            B --> A --> B

        Trace:
            B
            A
            B
            ...
        """
          .trimIndent()
      )
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
    bindingGraph.seal(shrinkUnusedBindings = false)

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
    bindingGraph.seal(shrinkUnusedBindings = false)

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
    // A -> Provider<A>
    val (graph, result) =
      buildGraph {
        // Create a direct cycle
        "A".dependsOn("Provider<A>")
      }

    with(graph) { assertThat("A".typeKey.dependsOn("Provider<A>".typeKey)).isTrue() }
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

        (Hint) Bindings are equal
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

        (Hint) Bindings are the same
        """
          .trimIndent()
      )
  }
}

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
    (StringContextualTypeKey, Set<StringTypeKey>, StringBindingStack) -> Set<StringBinding> =
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

private fun buildGraph(
  body: StringGraphBuilder.() -> Unit
): Pair<StringGraph, GraphTopology<StringTypeKey>> {
  return StringGraphBuilder().apply(body).sealAndReturn()
}

// Helper method to create a graph with a chain of dependencies
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

  fun binding(key: String): String {
    binding(key.contextualTypeKey)
    return key
  }

  fun binding(contextKey: StringContextualTypeKey): StringContextualTypeKey {
    tryPut(contextKey.typeKey.toBinding())
    return contextKey
  }

  fun tryPut(binding: StringBinding) {
    graph.tryPut(binding)
  }

  infix fun String.dependsOn(other: String): String {
    typeKey.dependsOn(other.contextualTypeKey)
    return other
  }

  infix fun StringTypeKey.dependsOn(other: String): String {
    dependsOn(other.contextualTypeKey)
    return other
  }

  infix fun StringTypeKey.dependsOn(other: StringTypeKey): StringTypeKey {
    dependsOn(other.contextualTypeKey)
    return other
  }

  infix fun StringTypeKey.dependsOn(other: StringContextualTypeKey): StringContextualTypeKey {
    val currentDeps = graph[this]?.dependencies.orEmpty()
    val newBinding = StringBinding(this, currentDeps + other)
    graph.replace(newBinding)
    if (other.typeKey !in graph && other.typeKey !in constructorInjectedTypes) {
      graph.tryPut(other.typeKey.toBinding())
    }
    return other
  }

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

  fun sealAndReturn(): Pair<StringGraph, GraphTopology<StringTypeKey>> {
    return graph to graph.seal(shrinkUnusedBindings = false)
  }
}
