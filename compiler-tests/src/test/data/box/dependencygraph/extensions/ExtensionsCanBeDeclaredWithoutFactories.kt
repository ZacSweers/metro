@GraphExtension
interface LoggedInGraph {
  val int: Int
}

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  fun loggedInGraph(): LoggedInGraph

  val loggedInGraphProp: LoggedInGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.loggedInGraph().int)
  assertEquals(3, graph.loggedInGraphProp.int)
  return "OK"
}
