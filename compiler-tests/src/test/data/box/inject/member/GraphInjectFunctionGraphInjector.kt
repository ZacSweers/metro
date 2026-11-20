package test

@DependencyGraph
interface ExampleGraph {
  fun inject(exampleClass: ExampleClass)

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides value: Int, @Provides value2: Long): ExampleGraph
  }
}

class ExampleClass {
  var long: Long = 0
  var int: Int = 0

  @Inject fun injectValues(long: Long, int: Int) {
    this.long = long
    this.int = int
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3, 4L)
  val instance = ExampleClass()
  graph.inject(instance)
  assertEquals(3, instance.int)
  assertEquals(4L, instance.long)
  return "OK"
}
