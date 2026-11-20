// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  <!SUSPICIOUS_OBJECT_INJECTION_WARNING!>fun <!DEPENDENCY_GRAPH_ERROR!>value<!>()<!>
}
