// ENABLE_SUSPEND_PROVIDERS

var stringComputations = 0

@Inject class ServiceB(val valueProvider: SuspendProvider<String>)

@DependencyGraph
interface ExampleGraph {
  val service: ServiceB

  @Provides
  suspend fun provideString(): String {
    stringComputations++
    return "transitive suspend"
  }
}

fun box(): String {
  stringComputations = 0
  val graph = createGraph<ExampleGraph>()
  val service = graph.service
  assertEquals(0, stringComputations)
  return runSuspending {
    assertEquals("transitive suspend", service.valueProvider())
    assertEquals(1, stringComputations)
    "OK"
  }
}
