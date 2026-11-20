// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(): <!GRAPH_CREATORS_ERROR!>Nothing<!>
  }
}
