package test

@DependencyGraph
interface ExampleGraph {
  fun inject(value: ExampleClass)

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides value: Int): ExampleGraph
  }
}

class ExampleClass {
  @Inject var int: Int = 2
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3)
  val instance = ExampleClass()
  graph.inject(instance)
  assertEquals(3, instance.int)
  return "OK"
}
