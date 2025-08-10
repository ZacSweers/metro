// https://github.com/ZacSweers/metro/issues/773
// MODULE: lib
@DependencyGraph
interface StaticGraph {
  val int: Int

  @Provides fun provideInt(): Int = 3

  companion object : StaticGraph by createGraph()
}

// MODULE: main(lib)
@DependencyGraph
interface StaticGraphWithFactory {
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun build(@Includes staticGraph: StaticGraph): StaticGraphWithFactory
  }
  companion object : StaticGraphWithFactory by createGraphFactory<Factory>().build(StaticGraph)
}

fun box(): String {
  val graph = StaticGraphWithFactory
  assertEquals(3, graph.int)
  return "OK"
}