package test

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides value: Int, @Provides value2: Long): ExampleGraph
  }
}

@Inject
class ExampleClass(val long: Long) {
  @Inject var int: Int = 2
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3, 4L)
  val instance = graph.exampleClass
  assertEquals(3, instance.int)
  assertEquals(4L, instance.long)
  return "OK"
}
