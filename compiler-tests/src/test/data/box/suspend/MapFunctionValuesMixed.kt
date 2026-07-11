// ENABLE_SUSPEND_PROVIDERS

// WITH_COROUTINES
// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

// The documented map multibinding form: Map<K, suspend () -> V> with mixed suspend and
// non-suspend contributions, invoked end to end.

@DependencyGraph
interface ExampleGraph {
  val handlers: Map<String, suspend () -> Int>

  @Provides @IntoMap @StringKey("suspend") suspend fun provideSuspendValue(): Int = 1

  @Provides @IntoMap @StringKey("plain") fun providePlainValue(): Int = 2
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return kotlinx.coroutines.runBlocking {
    val handlers = graph.handlers
    assertEquals(setOf("suspend", "plain"), handlers.keys)
    assertEquals(1, handlers.getValue("suspend").invoke())
    assertEquals(2, handlers.getValue("plain").invoke())
    "OK"
  }
}
