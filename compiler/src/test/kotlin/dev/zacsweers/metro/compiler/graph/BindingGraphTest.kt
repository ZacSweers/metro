package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

internal typealias StringGraph =
  BindingGraph<
    String,
    StringTypeKey,
    StringContextualTypeKey,
    BaseBinding<String, StringTypeKey, StringContextualTypeKey>,
    StringBindingStack.Entry,
    StringBindingStack,
  >

class BindingGraphTest {

  @Test
  fun put() {
    val key = "key".typeKey
    val graph = buildGraph { binding("key") }

    assertTrue(key in graph)
  }

  @Test
  fun `put throws if graph is sealed`() {
    val graph = buildGraph { binding("key") }

    val exception = assertFailsWith<IllegalStateException> { graph.put("key".typeKey.toBinding()) }
    assertThat(exception).hasMessageThat().contains("Graph already sealed")
  }

  @Test
  fun `seal processes dependencies and marks graph as sealed`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val graph = buildGraph { a dependsOn b }

    with(graph) {
      assertThat(a.dependsOn(b)).isTrue()
      assertThat(graph.sealed).isTrue()
    }
  }

  @Test
  fun `TypeKey dependsOn withDeferrableTypes`() {
    val aProvider = "Provider<A>".typeKey
    val lazyB = "Lazy<B>".typeKey
    val a = "A".typeKey
    val b = "B".typeKey

    val graph = buildGraph {
      a dependsOn aProvider
      b dependsOn lazyB
    }

    with(graph) {
      assertThat(a.dependsOn(aProvider)).isTrue()
      assertThat(b.dependsOn(lazyB)).isTrue()
    }

    assertThat(graph.deferredTypes).isEmpty()
  }

  @Test
  fun `seal deferrableTypeDependencyGraph`() {
    val aProvider = "Provider<A>".typeKey
    val b = "B".typeKey

    val aProviderBinding = aProvider.toBinding(b)
    val bBinding = b.toBinding()
    val bindingGraph = newStringBindingGraph()

    bindingGraph.put(aProviderBinding)
    bindingGraph.put(bBinding)
    bindingGraph.seal()

    with(bindingGraph) { assertThat(aProvider.dependsOn(b)).isTrue() }

    assertThat(bindingGraph.deferredTypes).isEmpty()
  }

  @Test
  fun `seal throws for strict dependency cycle`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val aBinding = a.toBinding(b)
    val bBinding = b.toBinding(a)
    val bindingGraph = newStringBindingGraph()

    bindingGraph.put(aBinding)
    bindingGraph.put(bBinding)

    val exception = assertFailsWith<IllegalStateException> { bindingGraph.seal() }
    assertThat(exception).hasMessageThat().contains("Strict dependency cycle")
  }

  @Test
  fun `TypeKey dependsOn returns true for dependent keys`() {
    val a = "A".typeKey
    val b = "B".typeKey
    val aBinding = a.toBinding(b)
    val bBinding = b.toBinding()
    val bindingGraph = newStringBindingGraph()

    bindingGraph.put(aBinding)
    bindingGraph.put(bBinding)
    bindingGraph.seal()

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

    bindingGraph.put(aBinding)
    bindingGraph.put(bBinding)
    bindingGraph.put(bindingC)
    bindingGraph.seal()

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
  fun `seal handles constructor injected types with dependencies`() {
    val c = "C".typeKey
    val d = "D".typeKey
    val e = "E".typeKey
    val a = "A".typeKey
    val dBinding = d.toBinding()
    val eBinding = e.toBinding(d)
    val cBinding = c.toBinding(e)

    val graph = buildGraph {
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
}

private val String.typeKey: StringTypeKey
  get() = StringTypeKey(this)

private val String.contextualTypeKey: StringContextualTypeKey
  get() = typeKey.contextualTypeKey

private val StringTypeKey.contextualTypeKey: StringContextualTypeKey
  get() = StringContextualTypeKey(this)

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
  computeBinding: (StringTypeKey) -> StringBinding? = { null },
): BindingGraph<
  String,
  StringTypeKey,
  StringContextualTypeKey,
  BaseBinding<String, StringTypeKey, StringContextualTypeKey>,
  StringBindingStack.Entry,
  StringBindingStack,
> {
  return BindingGraph(
    newBindingStack = { StringBindingStack(graph) },
    newBindingStackEntry = { StringBindingStack.Entry(it.contextualTypeKey) },
    computeBinding = computeBinding,
  )
}

private fun buildGraph(body: StringGraphBuilder.() -> Unit): StringGraph {
  return StringGraphBuilder().apply(body).sealAndReturn()
}

internal class StringGraphBuilder {
  private val constructorInjectedTypes = mutableMapOf<StringTypeKey, StringBinding>()
  private val graph = newStringBindingGraph() { constructorInjectedTypes[it] }

  fun binding(key: String): String {
    binding(key.typeKey)
    return key
  }

  fun binding(typeKey: StringTypeKey): StringTypeKey {
    graph.put(typeKey.toBinding())
    return typeKey
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
    graph.put(toBinding(currentDeps + other))
    if (other.typeKey !in graph && other.typeKey !in constructorInjectedTypes) {
      graph.put(other.typeKey.toBinding())
    }
    return other
  }

  infix fun StringBinding.dependsOn(other: StringContextualTypeKey): StringContextualTypeKey {
    val currentDeps = dependencies
    graph.put(typeKey.toBinding(currentDeps + other))
    if (other.typeKey !in graph) {
      graph.put(other.typeKey.toBinding())
    }
    return other
  }

  fun constructorInjected(key: StringTypeKey) {
    constructorInjected(key.toBinding())
  }

  fun constructorInjected(binding: StringBinding) {
    constructorInjectedTypes[binding.typeKey] = binding
  }

  fun sealAndReturn(): StringGraph {
    graph.seal()
    return graph
  }
}
