@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes stringBindings: StringBindings): AppGraph
  }
}

class StringBindings(val value: String) {
  @Provides
  fun provideString(): String {
    return "string value: $value"
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>(StringBindings("hello"))
  assertEquals("string value: hello", graph.string)
  return "OK"
}