@GraphExtension(Unit::class)
interface UnitGraph {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createUnitGraph(): UnitGraph
  }
}

@DependencyGraph(AppScope::class)
interface ExampleGraph

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val factory = graph.asContribution<UnitGraph.Factory>()
  assertSame(graph, factory)
  assertNotNull(factory.createUnitGraph())
  return "OK"
}
