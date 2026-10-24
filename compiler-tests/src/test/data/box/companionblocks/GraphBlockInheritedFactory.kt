// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

interface BaseFactory<T, R> {
  fun create(@Provides value: T): R
}

interface DefaultFactory<R> {
  fun build(@Provides value: String = "inherited"): R
}

@DependencyGraph
interface DefaultGraph {
  val value: String

  @DependencyGraph.Factory
  interface Factory : DefaultFactory<DefaultGraph> {
    override fun build(@Provides value: String): DefaultGraph
  }
}

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @DependencyGraph.Factory
  interface Factory : BaseFactory<Int, ExampleGraph>
}

fun box(): String {
  assertEquals("inherited", DefaultGraph.build().value)
  assertEquals(3, ExampleGraph.create(3).value)
  assertEquals(5, createGraphFactory<ExampleGraph.Factory>().create(5).value)
  val creator: (Int) -> ExampleGraph = ExampleGraph::create
  assertEquals(7, creator(7).value)
  return "OK"
}
