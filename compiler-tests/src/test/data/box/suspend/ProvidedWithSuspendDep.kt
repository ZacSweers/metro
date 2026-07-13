// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<Int>

  @Provides suspend fun provideInt(dep: String): Int = dep.length

  @Provides suspend fun provideString(): String = "hello"
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  val provider = createGraph<ExampleGraph>().provider
  return runSuspending {
    assertEquals(5, provider())
    "OK"
  }
}
