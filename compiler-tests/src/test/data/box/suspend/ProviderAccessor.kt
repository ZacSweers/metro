// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<String>

  @Provides suspend fun provideValue(): String = "Hello, suspend!"
}

fun box(): String {
  val provider = createGraph<ExampleGraph>().provider
  return runSuspending {
    assertEquals("Hello, suspend!", provider())
    "OK"
  }
}
