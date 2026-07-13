// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

var stringComputations = 0

@Inject class ServiceB(val valueProvider: SuspendProvider<String>)

@DependencyGraph
interface ExampleGraph {
  val service: ServiceB

  @Provides
  suspend fun provideString(): String {
    stringComputations++
    return "transitive suspend"
  }
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  stringComputations = 0
  val graph = createGraph<ExampleGraph>()
  val service = graph.service
  assertEquals(0, stringComputations)
  return runSuspending {
    assertEquals("transitive suspend", service.valueProvider())
    assertEquals(1, stringComputations)
    "OK"
  }
}
