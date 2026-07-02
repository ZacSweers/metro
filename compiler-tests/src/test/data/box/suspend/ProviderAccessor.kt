@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<String>

  @Provides suspend fun provideValue(): String = "Hello, suspend!"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val provider = graph.provider
  assertNotNull(provider)
  return "OK"
}
