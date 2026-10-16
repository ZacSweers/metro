// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPATIBILITY
// GENERATE_CLASSES_IN_IR: true

@DependencyGraph
interface BlockGraph {
  companion {
    operator fun <!DEPENDENCY_GRAPH_ERROR!>invoke<!>(): BlockGraph = error("user implementation")
  }
}
