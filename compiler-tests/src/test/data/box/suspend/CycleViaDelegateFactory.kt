// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// Tests cycle-breaking with suspend bindings: a cycle between two suspend @Provides functions
// where each wraps the other in SuspendProvider. The compiler must use SuspendDelegateFactory
// (rather than DelegateFactory) for the deferred binding so the field type matches the
// SuspendProvider<T> property type.

var firstComputations = 0
var secondComputations = 0

@DependencyGraph
interface ExampleGraph {
  val firstProvider: SuspendProvider<String>

  @Provides
  suspend fun provideFirst(second: SuspendProvider<Int>): String {
    firstComputations++
    return if (firstComputations == 1) "first:${second()}" else "back-edge"
  }

  @Provides
  suspend fun provideSecond(first: SuspendProvider<String>): Int {
    secondComputations++
    return if (first() == "back-edge") 2 else -1
  }
}

private fun <T> runSuspending(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  firstComputations = 0
  secondComputations = 0
  val graph = createGraph<ExampleGraph>()
  assertEquals("first:2", runSuspending { graph.firstProvider() })
  assertEquals(2, firstComputations)
  assertEquals(1, secondComputations)
  return "OK"
}
