@GraphExtension(Unit::class)
interface LoggedInGraph : ManualBindings {
  val int: Int

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

interface ManualBindings : ViewManagerObjectGraphSubgraph

@ContributesTo(AppScope::class)
interface ViewManagerObjectGraphSubgraph {
  val loggedInGraphFactory: LoggedInGraph.Factory
}

@DependencyGraph(AppScope::class)
interface ProfileGraph {
  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<ProfileGraph>()
  val factory = graph.loggedInGraphFactory
  val loggedInGraph = factory.createLoggedInGraph()
  val factory2 = loggedInGraph.loggedInGraphFactory
  assertSame(factory, factory2)
  val loggedInGraph2 = factory2.createLoggedInGraph()
  assertNotSame(loggedInGraph, loggedInGraph2)
  assertEquals(3, loggedInGraph.int)
  assertEquals(3, loggedInGraph2.int)
  return "OK"
}
