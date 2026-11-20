// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: Int, @Provides <!GRAPH_CREATORS_ERROR!>value2<!>: Int): ExampleGraph
  }
}
