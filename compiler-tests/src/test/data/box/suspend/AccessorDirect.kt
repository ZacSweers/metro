// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// Tests that a suspend accessor can directly return T from a suspend @Provides.

@DependencyGraph
interface ExampleGraph {
  suspend fun getValue(): String

  @Provides suspend fun provideValue(): String = "suspend direct"
}

private fun <T> runSuspending(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("suspend direct", runSuspending { graph.getValue() })
  return "OK"
}
