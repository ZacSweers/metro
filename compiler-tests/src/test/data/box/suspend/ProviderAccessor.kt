// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<String>

  @Provides suspend fun provideValue(): String = "Hello, suspend!"
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  val provider = createGraph<ExampleGraph>().provider
  return runSuspending {
    assertEquals("Hello, suspend!", provider())
    "OK"
  }
}
