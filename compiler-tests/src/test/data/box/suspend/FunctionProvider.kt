// ENABLE_SUSPEND_PROVIDERS

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
  val provider = graph.suspendStringProvider
  assertEquals("suspend function provider", runBlocking { provider() })
  return "OK"
}
