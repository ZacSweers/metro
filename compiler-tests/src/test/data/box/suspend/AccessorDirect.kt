// Tests that a suspend accessor can directly return T from a suspend @Provides.
// We can't invoke the accessor from box() since it's not suspend,
// but graph creation validates the binding graph.

@DependencyGraph
interface ExampleGraph {
  suspend fun getValue(): String

  @Provides suspend fun provideValue(): String = "suspend direct"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
