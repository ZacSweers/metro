// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraphWithFactory {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): ExampleGraphWithFactory
  }
}

fun example() {
  val exampleGraph = createGraph<<!CREATE_GRAPH_ERROR!>ExampleGraphWithFactory<!>>()
  val exampleGraph2: ExampleGraphWithFactory = <!CREATE_GRAPH_ERROR!>createGraph<!>()
}
