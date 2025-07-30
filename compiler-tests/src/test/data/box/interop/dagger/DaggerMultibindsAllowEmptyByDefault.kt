// ENABLE_DAGGER_INTEROP

// Regression test for https://github.com/ZacSweers/metro/issues/334

import dagger.multibindings.Multibinds

@DependencyGraph
interface ExampleGraph {
  @Multibinds fun emptySet(): Set<Int>
  @Multibinds fun emptyMap(): Map<String, Int>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertTrue(graph.emptySet().isEmpty())
  assertTrue(graph.emptyMap().isEmpty())
  return "OK"
}
