// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPATIBILITY
// GENERATE_CLASSES_IN_IR: true

// A companion that implements the graph exposes factory(), so the static creator builds the graph.
@DependencyGraph
internal interface StaticGraph {
  val int: Int

  @Provides fun provideInt(): Int = 3

  companion object : StaticGraph by createGraph()
}

@DependencyGraph
internal interface StaticGraphWithFactory {
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun build(@Includes staticGraph: StaticGraph): StaticGraphWithFactory
  }

  companion object : StaticGraphWithFactory by createGraphFactory<Factory>().build(StaticGraph)
}

fun box(): String {
  assertEquals(3, StaticGraphWithFactory.int)
  assertEquals(3, StaticGraphWithFactory.build(StaticGraph).int)
  return "OK"
}
