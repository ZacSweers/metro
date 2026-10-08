// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPATIBILITY

@DependencyGraph
interface BlockGraph {
  companion {
    operator fun <!DEPENDENCY_GRAPH_ERROR!>invoke<!>(): BlockGraph = error("user implementation")
  }
}
