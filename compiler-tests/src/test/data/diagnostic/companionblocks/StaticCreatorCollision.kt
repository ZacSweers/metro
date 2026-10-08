// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
@DependencyGraph
interface AppGraph {
  companion {
    operator fun <!DEPENDENCY_GRAPH_ERROR!>invoke<!>(): AppGraph = error("user implementation")
  }
}

@DependencyGraph
interface AbstractFactoryGraph {
  @DependencyGraph.Factory
  abstract class Factory {
    abstract fun create(@Provides value: Int): AbstractFactoryGraph
  }

  companion {
    fun <!DEPENDENCY_GRAPH_ERROR!>factory<!>(): Factory = error("user implementation")
  }
}

// Different parameters can share the generated creator's name.
@DependencyGraph
interface OverloadedGraph {
  companion {
    operator fun invoke(value: Int): OverloadedGraph = error("static overload")
  }
}
