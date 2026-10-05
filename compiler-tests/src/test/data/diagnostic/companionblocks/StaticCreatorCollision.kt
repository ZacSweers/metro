// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
@DependencyGraph
interface AppGraph {
  companion {
    operator fun <!DEPENDENCY_GRAPH_ERROR!>invoke<!>(): AppGraph = error("user implementation")
  }
}
