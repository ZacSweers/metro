// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// A class injecting Map<K, suspend () -> V> resolves the map synchronously — each value defers
// its own suspension. The class must NOT be marked transitively suspend, so a non-suspend
// accessor over it is valid.

@Inject class Registry(val handlers: Map<String, suspend () -> Int>)

@DependencyGraph
interface ExampleGraph {
  // Non-suspend accessor: Registry construction doesn't suspend
  val registry: Registry

  val handlersProvider: () -> Map<String, suspend () -> Int>

  @Provides @IntoMap @StringKey("suspend") suspend fun provideSuspendValue(): Int = 1

  @Provides @IntoMap @StringKey("plain") fun providePlainValue(): Int = 2
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val registry = graph.registry
  val block: suspend () -> String = {
    assertEquals(1, registry.handlers.getValue("suspend").invoke())
    assertEquals(2, registry.handlers.getValue("plain").invoke())
    val handlers = graph.handlersProvider()
    assertEquals(1, handlers.getValue("suspend").invoke())
    assertEquals(2, handlers.getValue("plain").invoke())
    "OK"
  }
  return runBlocking(block)
}
