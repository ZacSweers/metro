// ENABLE_SUSPEND_PROVIDERS

// Tests that a suspend accessor can directly return T from a suspend @Provides.

@DependencyGraph
interface ExampleGraph {
  suspend fun getValue(): String

  @Provides suspend fun provideValue(): String = "suspend direct"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("suspend direct", runSuspending { graph.getValue() })
  return "OK"
}
