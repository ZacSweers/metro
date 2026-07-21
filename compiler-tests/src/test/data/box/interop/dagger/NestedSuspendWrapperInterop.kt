// ENABLE_DAGGER_INTEROP
// ENABLE_SUSPEND_PROVIDERS
import dagger.Lazy
import javax.inject.Provider
var interopComputations = 0

class InteropValue(val index: Int)

@Inject
class InteropConsumer(
  val providerOfSuspendLazy: Provider<SuspendLazy<InteropValue>>,
  val lazyOfSuspendFunction: Lazy<suspend () -> InteropValue>,
)

@DependencyGraph
interface InteropGraph {
  val consumer: InteropConsumer

  @Provides
  suspend fun provideValue(): InteropValue {
    interopComputations++
    return InteropValue(interopComputations)
  }
}

fun box(): String =
  runBlocking {
    interopComputations = 0
    val consumer = createGraph<InteropGraph>().consumer

    val suspendLazy = consumer.providerOfSuspendLazy.get()
    assertEquals(0, interopComputations)
    assertEquals(1, suspendLazy.value().index)
    assertEquals(1, suspendLazy.value().index)

    val suspendFunction: suspend () -> InteropValue = consumer.lazyOfSuspendFunction.get()
    assertEquals(2, suspendFunction().index)
    assertEquals(3, suspendFunction().index)

    "OK"
  }
