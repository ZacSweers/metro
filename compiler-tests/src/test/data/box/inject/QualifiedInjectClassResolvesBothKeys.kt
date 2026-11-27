// One graph requests a qualified @Inject class with its qualifier and with none.

@Qualifier annotation class Chosen

@Chosen @Inject class Engine

@DependencyGraph
interface AppGraph {
  val engine: Engine

  @Chosen val chosenEngine: Engine
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.engine)
  assertNotNull(graph.chosenEngine)

  // The class has no scope, so each request gets a new instance.
  assertNotSame(graph.engine, graph.chosenEngine)
  return "OK"
}
