// https://github.com/ZacSweers/metro/issues/1143

@DependencyGraph(AppScope::class)
interface AppGraph {
  val loggedInGraphFactory: LoggedInGraph.Factory

  fun userGraphFactory(): LoggedInGraph.Factory
}

@GraphExtension
interface LoggedInGraph {
  @GraphExtension.Factory
  interface Factory {
    fun create(): LoggedInGraph
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.loggedInGraphFactory)
  assertNotNull(graph.userGraphFactory())
  return "OK"
}
