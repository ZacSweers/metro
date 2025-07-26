// Ensures that even if we @Includes a graph, it's ok for us to pass non-metro-generated instances
// because this should just use its public accessors API
@DependencyGraph
interface SourceGraph {
  val int: Int

  @Provides fun provideInt(): Int = 1
}

@DependencyGraph
interface AppGraph {
  val int: Int
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes source: SourceGraph): AppGraph
  }
}

fun box(): String {
  val source = object : SourceGraph {
    override val int: Int = 3
  }
  val appGraph = createGraphFactory<AppGraph.Factory>().create(source)
  assertEquals(3, appGraph.int)
  return "OK"
}