abstract class LoggedInScope
abstract class AnotherGraphScope

@GraphExtension
@SingleIn(LoggedInScope::class)
interface LoggedInGraph {
  val int: Int
}

@GraphExtension
@SingleIn(AnotherGraphScope::class)
interface AnotherGraph {
  val value: String

  @Provides fun value(loggedInGraph: LoggedInGraph): String = loggedInGraph.int.toString()
}

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  val loggedInGraph: LoggedInGraph
  val anotherGraph: AnotherGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val loggedInGraph = graph.loggedInGraph
  assertEquals(3, loggedInGraph.int)
  assertNotSame(loggedInGraph, graph.loggedInGraph)
//  val anotherGraph = graph.anotherGraph
//  assertNotSame(anotherGraph, graph.anotherGraph)
//  assertEquals("3", anotherGraph.value)
  return "OK"
}
