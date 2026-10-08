// MIN_COMPILER_VERSION: 2.5.0-Beta1
// METRO_JVM_ONLY
// COMPANION_MODE: COMPATIBILITY

@DependencyGraph
interface NamedFactoryGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun build(@Provides value: Int): NamedFactoryGraph
  }

  companion object {
    @JvmStatic
    fun <!DEPENDENCY_GRAPH_ERROR, VIRTUAL_MEMBER_HIDDEN!>build<!>(value: Int): NamedFactoryGraph = error("user implementation")
  }
}

@DependencyGraph
interface AppGraph {
  companion object {
    @JvmStatic
    operator fun <!DEPENDENCY_GRAPH_ERROR!>invoke<!>(): AppGraph = error("user implementation")
  }
}
