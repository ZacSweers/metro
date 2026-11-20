// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  fun value(): <!DEPENDENCY_GRAPH_ERROR, SUSPICIOUS_OBJECT_INJECTION_WARNING!>Unit<!>
}
