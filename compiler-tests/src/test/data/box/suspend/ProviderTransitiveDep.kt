// Tests that SuspendProvider<T> properly breaks the suspend chain.
// ServiceB wraps its suspend dependency in SuspendProvider, so
// ServiceB itself does not require a suspend context.

@Inject class ServiceB(val valueProvider: SuspendProvider<String>)

@DependencyGraph
interface ExampleGraph {
  // Non-suspend accessor — ServiceB's suspend dep is wrapped in SuspendProvider
  val service: ServiceB

  @Provides suspend fun provideString(): String = "transitive suspend"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val service = graph.service
  assertNotNull(service.valueProvider)
  return "OK"
}
