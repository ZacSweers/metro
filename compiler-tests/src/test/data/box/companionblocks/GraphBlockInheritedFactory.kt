// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK

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
  assertEquals(3, ExampleGraph.create(3).value)
  assertEquals(5, createGraphFactory<ExampleGraph.Factory>().create(5).value)
  val creator: (Int) -> ExampleGraph = ExampleGraph::create
  assertEquals(7, creator(7).value)
  return "OK"
}
