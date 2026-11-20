// A supplied constructor-injected root already has a binding and should not be recomputed.

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val value: Dependency

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: Dependency): ExampleGraph
  }
}

@Inject class Dependency

fun box(): String {
  val input = Dependency()
  val graph = createGraphFactory<ExampleGraph.Factory>().create(input)
  assertSame(input, graph.value)
  return "OK"
}
