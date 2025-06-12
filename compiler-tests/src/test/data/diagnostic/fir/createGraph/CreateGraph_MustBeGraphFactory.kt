// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface ExampleGraphWithFactory {
  interface Factory {
    fun create(): ExampleGraphWithFactory
  }
}

fun example() {
  val exampleGraphWithFactory = createGraphFactory<<!CREATE_GRAPH_ERROR!>ExampleGraphWithFactory<!>>()
  val exampleGraphFactory: ExampleGraphWithFactory = <!CREATE_GRAPH_ERROR!>createGraphFactory<!>()
}
