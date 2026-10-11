@GraphExtension
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun createChild(): ChildGraph
  }
}

@DependencyGraph
interface IncludedGraph : ChildGraph.Factory {
  val string: String

  @Provides fun provideString(): String = "included"
}

@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes included: IncludedGraph): AppGraph
  }
}

fun box(): String {
  val included = createGraph<IncludedGraph>()
  val graph = createGraphFactory<AppGraph.Factory>().create(included)
  assertEquals("included", graph.string)
  assertEquals("included", included.createChild().string)
  return "OK"
}
