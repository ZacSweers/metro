@DependencyGraph(AppScope::class)
interface ExampleGraph {
  fun exampleClass(): ExampleClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides text: String): ExampleGraph
  }
}

@SingleIn(AppScope::class)
@Inject
class ExampleClass(private val text: String) {
  fun call(): String = text
}

private fun createExampleClass(): (String) -> ExampleClass {
  val factory = createGraphFactory<ExampleGraph.Factory>()
  return { factory.create(it).exampleClass() }
}

fun box(): String {
  val factory = createGraphFactory<ExampleGraph.Factory>()
  val graph = factory.create("Hello, world!")
  assertEquals("Hello, world!", graph.exampleClass().call())

  // A caller can also keep the factory for later invocations.
  val create = createExampleClass()
  assertEquals("Hello, world!", create("Hello, world!").call())
  return "OK"
}
