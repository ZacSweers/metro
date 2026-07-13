// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

var functionComputations = 0

class FunctionValue(val index: Int)

@Inject
class Consumer(val value: () -> suspend () -> FunctionValue)

@DependencyGraph
interface ExampleGraph {
  val consumer: Consumer

  val functionOfSuspendFunction: () -> suspend () -> FunctionValue

  val suspendFunctionOfSuspendFunction: suspend () -> suspend () -> FunctionValue

  @Provides
  suspend fun provideValue(): FunctionValue {
    functionComputations++
    return FunctionValue(functionComputations)
  }
}

fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String =
  runSuspending {
    functionComputations = 0
    val graph = createGraph<ExampleGraph>()

    val directInner: suspend () -> FunctionValue = graph.functionOfSuspendFunction()
    val injectedInner: suspend () -> FunctionValue = graph.consumer.value()
    val suspendInner: suspend () -> FunctionValue = graph.suspendFunctionOfSuspendFunction()
    assertEquals(0, functionComputations)

    assertEquals(1, directInner().index)
    assertEquals(2, injectedInner().index)
    assertEquals(3, suspendInner().index)

    "OK"
  }
