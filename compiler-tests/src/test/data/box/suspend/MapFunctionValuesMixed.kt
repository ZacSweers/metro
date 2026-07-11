// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

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
  val block: suspend () -> String = {
    val handlers = graph.handlers
    assertEquals(setOf("suspend", "plain"), handlers.keys)
    assertEquals(1, handlers.getValue("suspend").invoke())
    assertEquals(2, handlers.getValue("plain").invoke())
    "OK"
  }
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}
