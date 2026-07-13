// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

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
