// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// ENABLE_FUNCTION_PROVIDERS
// Test that suspend () -> T works as an injection type (parallel to () -> T)
@DependencyGraph
interface ExampleGraph {
  // Accessor returning suspend () -> T
  val suspendStringProvider: suspend () -> String

  @Provides suspend fun provideString(): String = "suspend function provider"
}

private fun <T> runSuspending(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val provider = graph.suspendStringProvider
  assertEquals("suspend function provider", runSuspending { provider() })
  return "OK"
}
