// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

var valueComputations = 0

@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<String>

  @Provides
  suspend fun provideValue(): String {
    valueComputations++
    return "Hello, suspend!"
  }
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  valueComputations = 0
  val graph = createGraph<ExampleGraph>()
  val provider = graph.provider
  assertEquals(0, valueComputations)
  return runSuspending {
    assertEquals("Hello, suspend!", provider())
    assertEquals(1, valueComputations)
    "OK"
  }
}
