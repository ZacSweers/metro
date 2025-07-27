// https://github.com/ZacSweers/metro/issues/801
@DependencyGraph
interface Graph1 {
  val value1: Int

  @Provides fun value1(): Int = 1
}

@DependencyGraph
interface Graph2 {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes graph1: Graph1): Graph2
  }

  val value2: Int
}

@DependencyGraph
interface Graph3 {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes graph2: Graph2): Graph3
  }

  val value3: Int
}

fun box(): String {
  val graph1 = createGraph<Graph1>()
  assertEquals(1, graph1.value1)
  val graph2 = createGraphFactory<Graph2.Factory>().create(graph1)
  assertEquals(1, graph2.value2)
  val graph3 = createGraphFactory<Graph3.Factory>().create(graph2)
  assertEquals(1, graph3.value3)
  return "OK"
}
