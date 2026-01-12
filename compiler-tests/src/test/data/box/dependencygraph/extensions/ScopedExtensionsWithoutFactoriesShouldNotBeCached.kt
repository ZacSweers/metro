abstract class LoggedInScope

@GraphExtension
@SingleIn(LoggedInScope::class)
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
  val loggedInGraph = graph.loggedInGraph()
  assertEquals(3, loggedInGraph.int)
  assertNotSame(loggedInGraph, graph.loggedInGraph())
  assertNotSame(graph.loggedInGraphProp, graph.loggedInGraph())
  return "OK"
}
