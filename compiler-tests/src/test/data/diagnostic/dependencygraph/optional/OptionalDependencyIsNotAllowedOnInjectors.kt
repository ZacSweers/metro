// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  @OptionalDependency
  fun <!DEPENDENCY_GRAPH_ERROR!>inject<!>(<!DEPENDENCY_GRAPH_ERROR!>@OptionalDependency<!> example: Example)
}

class Example {

}
