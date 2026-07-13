// ENABLE_SUSPEND_PROVIDERS

var valueComputations = 0

@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<String>

  @Provides
  suspend fun provideValue(): String {
    valueComputations++
    return "Hello, suspend!"
  }
}

fun box(): String {
  valueComputations = 0
  val graph = createGraph<ExampleGraph>()
  val provider = graph.provider
  assertEquals(0, valueComputations)
  return runSuspending {
    assertEquals("Hello, suspend!", provider())
    assertEquals(1, valueComputations)
    "OK"
  }
}
