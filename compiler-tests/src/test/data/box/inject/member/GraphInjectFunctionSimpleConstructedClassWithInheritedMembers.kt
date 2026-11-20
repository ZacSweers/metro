package test

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides int: Int, @Provides long: Long): ExampleGraph
  }
}

@HasMemberInjections
  abstract class Base {
  @Inject var baseLong: Long = 0L
}

@Inject
class ExampleClass(val long: Long) : Base() {
  @Inject var int: Int = 2
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3, 4L)
  val instance = graph.exampleClass
  assertEquals(3, instance.int)
  assertEquals(4L, instance.long)
  assertEquals(4L, instance.baseLong)
  return "OK"
}
