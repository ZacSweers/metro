// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides value: String, <!GRAPH_CREATORS_ERROR!>value2<!>: Int): ExampleGraph
  }
}
