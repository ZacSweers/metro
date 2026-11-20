// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  fun <!DEPENDENCY_GRAPH_ERROR!>value<!>(): Nothing
}
