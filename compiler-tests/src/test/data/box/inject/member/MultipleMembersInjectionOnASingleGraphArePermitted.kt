package test

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides value: Any): ExampleGraph
  }
  fun inject(exampleClass1: ExampleClass)
  fun inject(exampleClass2: ExampleClass2)
}

class ExampleClass {
  @Inject lateinit var value: Any
}
class ExampleClass2 {
  @Inject lateinit var value: Any
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(3)
  val instance = ExampleClass()
  val instance2 = ExampleClass2()
  graph.inject(instance)
  graph.inject(instance2)
  assertEquals(3, instance.value)
  assertEquals(3, instance2.value)
  return "OK"
}
