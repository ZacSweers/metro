// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  @OptionalBinding
  fun <!DEPENDENCY_GRAPH_ERROR!>inject<!>(<!DEPENDENCY_GRAPH_ERROR!>@OptionalBinding<!> example: Example)
}

class Example {

}
