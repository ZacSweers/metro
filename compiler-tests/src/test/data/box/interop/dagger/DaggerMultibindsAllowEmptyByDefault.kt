// ENABLE_DAGGER_INTEROP
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
