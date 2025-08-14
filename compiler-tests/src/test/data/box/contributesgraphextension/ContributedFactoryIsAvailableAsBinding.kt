// https://github.com/ZacSweers/metro/issues/712
// MODULE: lib
abstract class ViewScope

@GraphExtension(ViewScope::class)
interface ViewObjectGraph {
  val int: Int

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(@Provides int: Int): ViewObjectGraph
  }
}

@ContributesTo(AppScope::class)
interface ViewObjectGraphSubgraph {
  val viewObjectGraphFactory: ViewObjectGraph.Factory
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val vog = graph.create(3)
  assertEquals(3, vog.int)
  val vog2 = graph.viewObjectGraphFactory.create(3)
  assertEquals(3, vog2.int)
  return "OK"
}