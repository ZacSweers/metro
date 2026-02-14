@DependencyGraph
interface AppGraph {
  fun loggedInGraph(): LoggedInGraph

  @Provides fun provideSize(map: Map<String, String>): Int = map.size

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides map: Map<String, String>): AppGraph
  }
}

@GraphExtension
interface LoggedInGraph {
  val example: Example
}

@Inject class Example(val mapSize: Int)

fun box(): String {
  val example =
    createGraphFactory<AppGraph.Factory>().create(map = emptyMap()).loggedInGraph().example

  assertEquals(0, example.mapSize)
  return "OK"
}
