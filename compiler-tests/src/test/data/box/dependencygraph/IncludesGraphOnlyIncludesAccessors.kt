@DependencyGraph
interface ExampleGraph {
  val factory: ExampleClass.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes dependentGraph: DependentGraph): ExampleGraph
  }

  class ExampleClass @AssistedInject constructor(@Assisted val intValue: Int, val message: String) {
    @AssistedFactory
    fun interface Factory {
      fun create(intValue: Int): ExampleClass
    }
  }
}

@DependencyGraph
interface DependentGraph {
  val message: String

  @Provides private fun provideMessage(): String = "Hello, world!"
}

fun box(): String {
  val dependentGraph = createGraph<DependentGraph>()
  val graph = createGraphFactory<ExampleGraph.Factory>().create(dependentGraph)
  val factory = graph.factory
  val exampleClass = factory.create(2)
  assertEquals(2, exampleClass.intValue)
  assertEquals("Hello, world!", exampleClass.message)
  return "OK"
}