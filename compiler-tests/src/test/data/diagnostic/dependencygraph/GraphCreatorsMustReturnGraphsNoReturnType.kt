// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  @DependencyGraph.Factory
  fun interface <!GRAPH_CREATORS_ERROR!>Factory<!> {
    fun create()
  }
}
