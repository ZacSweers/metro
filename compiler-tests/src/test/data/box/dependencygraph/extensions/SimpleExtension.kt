@GraphExtension
interface LoggedInGraph {
  val id: String
  val count: Int

  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(@Provides id: String): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory {
  @Provides fun provideCount(): Int = 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val loggedInGraph = graph.createLoggedInGraph("123")
  assertEquals("123", loggedInGraph.id)
  assertEquals(3, loggedInGraph.count)
  return "OK"
}