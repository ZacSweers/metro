// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// Test for Map<K, SuspendProvider<V>> multibindings
@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @IntKey(0) suspend fun provideInt0(): Int = 0

  @Provides @IntoMap @IntKey(1) suspend fun provideInt1(): Int = 1

  @Provides @IntoMap @IntKey(2) suspend fun provideInt2(): Int = 2

  // Map with SuspendProvider values
  val suspendProviderInts: Map<Int, SuspendProvider<Int>>

  // Provider wrapping map with SuspendProvider values
  val providerOfSuspendProviderInts: Provider<Map<Int, SuspendProvider<Int>>>
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String =
  runSuspending {
    val graph = createGraph<ExampleGraph>()
    val expected = mapOf(0 to 0, 1 to 1, 2 to 2)

    // Test Map<Int, SuspendProvider<Int>>
    assertEquals(
      expected,
      graph.suspendProviderInts.mapValues { (_, suspendProvider) -> suspendProvider() },
    )

    // Test Provider<Map<Int, SuspendProvider<Int>>>
    assertEquals(
      expected,
      graph
        .providerOfSuspendProviderInts()
        .mapValues { (_, suspendProvider) -> suspendProvider() },
    )

    "OK"
  }
