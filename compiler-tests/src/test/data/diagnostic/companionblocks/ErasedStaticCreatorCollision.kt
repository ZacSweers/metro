// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// METRO_JVM_ONLY
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// The JVM erases type arguments, so these signatures clash.
@DependencyGraph
interface ErasedGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun build(@Provides values: List<String>): ErasedGraph
  }

  companion {
    fun <!DEPENDENCY_GRAPH_ERROR!>build<!>(values: List<Int>): ErasedGraph = error("user implementation")
  }
}

// Int and Int? compile to different JVM types, so these signatures don't clash.
@DependencyGraph
interface BoxedGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun build(@Provides value: Int): BoxedGraph
  }

  companion {
    fun build(value: Int?): BoxedGraph = error("user implementation")
  }
}
