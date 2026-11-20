// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  fun inject(target: Int): <!DEPENDENCY_GRAPH_ERROR!>Int<!>
}
