// A non-suspend accessor can request SuspendProvider<T> even though
// T is provided by a suspend function. SuspendProvider defers the call.

@DependencyGraph
interface ExampleGraph {
  // Non-suspend accessor returning SuspendProvider — this breaks the suspend chain
  val provider: SuspendProvider<String>

  @Provides suspend fun provideValue(): String = "Hello, suspend!"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val provider = graph.provider
  assertNotNull(provider)
  return "OK"
}
