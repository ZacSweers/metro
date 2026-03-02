// ENABLE_FUNCTION_PROVIDERS
// Test that suspend () -> T works as an injection type (parallel to () -> T)
@DependencyGraph
interface ExampleGraph {
  // Accessor returning suspend () -> T
  val suspendStringProvider: suspend () -> String

  @Provides suspend fun provideString(): String = "suspend function provider"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Can't invoke since it's suspend, but graph creation validates the binding graph
  assertNotNull(graph.suspendStringProvider)
  return "OK"
}
