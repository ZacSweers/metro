// https://github.com/ZacSweers/metro/issues/940
@DependencyGraph(AppScope::class)
interface AppGraph

@GraphExtension
interface ExtGraph {
  val value: Int

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory : BaseFactory {
    fun create(@Provides value: Int): ExtGraph

    override fun create(
      name: String,
      value: Int
    ): ExtGraph {
      return create(value)
    }
  }
}

interface BaseFactory {
  fun create(name: String, value: Int): ExtGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val extGraph = graph.create("Hello", 3)
  assertEquals(3, extGraph.value)
  return "OK"
}