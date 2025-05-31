interface BaseFactory<T, R> {
  fun create(@Provides value: T): R
}

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @DependencyGraph.Factory
  interface Factory : BaseFactory<Int, ExampleGraph>
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3)
  assertEquals(graph.value, 3)
  return "OK"
}