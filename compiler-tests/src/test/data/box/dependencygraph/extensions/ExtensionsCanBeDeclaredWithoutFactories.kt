@GraphExtension
interface LoggedInGraph {
  val int: Int
}

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  fun loggedInGraph(): LoggedInGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val loggedInGraph = graph.loggedInGraph()
  assertEquals(3, loggedInGraph.int)
  return "OK"
}
