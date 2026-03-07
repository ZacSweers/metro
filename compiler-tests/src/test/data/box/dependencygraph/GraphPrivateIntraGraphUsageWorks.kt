// Intra-graph usage of @GraphPrivate binding works fine
@DependencyGraph
interface TestGraph {
  val number: Int

  @GraphPrivate @Provides fun provideString(): String = "hello"
  @Provides fun provideInt(value: String): Int = value.length
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  assertEquals(5, graph.number)
  return "OK"
}
