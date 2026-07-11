// ENABLE_SUSPEND_PROVIDERS

// WITH_COROUTINES
// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

// A class injecting Map<K, suspend () -> V> resolves the map synchronously — each value defers
// its own suspension. The class must NOT be marked transitively suspend, so a non-suspend
// accessor over it is valid.

@Inject class Registry(val handlers: Map<String, suspend () -> Int>)

@DependencyGraph
interface ExampleGraph {
  // Non-suspend accessor: Registry construction doesn't suspend
  val registry: Registry

  @Provides @IntoMap @StringKey("suspend") suspend fun provideSuspendValue(): Int = 1

  @Provides @IntoMap @StringKey("plain") fun providePlainValue(): Int = 2
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val registry = graph.registry
  return kotlinx.coroutines.runBlocking {
    assertEquals(1, registry.handlers.getValue("suspend").invoke())
    assertEquals(2, registry.handlers.getValue("plain").invoke())
    "OK"
  }
}
