@DependencyGraph
interface ExampleGraph

@DependencyGraph
interface ExampleGraphWithFactory {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): ExampleGraphWithFactory
  }
}

fun example() {
  val exampleGraph = createGraph<ExampleGraph>()
  val exampleGraphWithFactory = createGraphFactory<ExampleGraphWithFactory.Factory>().create()
  val exampleGraph2: ExampleGraph = createGraph()
  val exampleGraphFactory: ExampleGraphWithFactory.Factory = createGraphFactory()
}
