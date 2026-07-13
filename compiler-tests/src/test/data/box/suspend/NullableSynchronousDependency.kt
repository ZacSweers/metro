// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class Result(val value: String?)

@DependencyGraph
interface ExampleGraph {
  suspend fun result(): Result

  @Provides fun provideNullableValue(): String? = null

  @Provides suspend fun provideResult(value: String?): Result = Result(value)
}

private fun runSuspending(block: suspend () -> String): String {
  var outcome: kotlin.Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
  return outcome!!.getOrThrow()
}

fun box(): String =
  runSuspending {
    val graph = createGraph<ExampleGraph>()
    assertNull(graph.result().value)
    "OK"
  }
