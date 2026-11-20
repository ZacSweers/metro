package test

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides @Named("int") value: Int, @Provides value2: Long): ExampleGraph
  }
}
@Inject
class ExampleClass {
  var long: Long = 0
  var int: Int = 0

  @Inject fun injectValues(long: Long, @Named("int") int: Int) {
    this.long = long
    this.int = int
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3, 4L)
  val instance = graph.exampleClass
  assertEquals(3, instance.int)
  assertEquals(4L, instance.long)
  return "OK"
}
