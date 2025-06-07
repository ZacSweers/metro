@DependencyGraph
interface ExampleGraph {
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes serviceProvider: FinalServiceProvider): ExampleGraph
  }
}

@ContributesTo(AppScope::class)
interface FinalServiceProvider : ScreenServiceProvider

interface ScreenServiceProvider : ChildServiceProvider

interface ChildServiceProvider {
  val int: Int
}

fun box(): String {
  val graphFactory = createGraphFactory<ExampleGraph.Factory>()
  val serviceProvider = object : FinalServiceProvider {
    override val int: Int get() = 3
  }
  val graph = graphFactory.create(serviceProvider)
  assertEquals(graph.int, 3)
  return "OK"
}