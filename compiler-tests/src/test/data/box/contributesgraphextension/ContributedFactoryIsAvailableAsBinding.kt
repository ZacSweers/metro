// https://github.com/ZacSweers/metro/issues/712
abstract class ViewScope

@ContributesGraphExtension(ViewScope::class, isExtendable = true)
interface ViewObjectGraph {
  val int: Int

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun create(@Provides int: Int): ViewObjectGraph
  }
}

@ContributesTo(AppScope::class)
interface ViewObjectGraphSubgraph {
  val viewObjectGraphFactory: ViewObjectGraph.Factory
}

@DependencyGraph(AppScope::class, isExtendable = true)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val vog = graph.create(3)
  assertEquals(3, vog.int)
  val vog2 = graph.viewObjectGraphFactory.create(3)
  assertEquals(3, vog2.int)
  return "OK"
}