// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides graph: <!GRAPH_CREATORS_ERROR!>ExampleGraph<!>): ExampleGraph
  }
}
