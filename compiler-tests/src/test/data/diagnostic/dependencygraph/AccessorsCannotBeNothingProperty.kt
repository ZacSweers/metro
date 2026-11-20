// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  val <!DEPENDENCY_GRAPH_ERROR!>value<!>: Nothing
}
