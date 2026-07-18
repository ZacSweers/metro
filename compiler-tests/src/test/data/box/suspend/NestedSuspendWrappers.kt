// ENABLE_SUSPEND_PROVIDERS
@file:Suppress("DESUGARED_PROVIDER_WARNING", "OPT_IN_USAGE")
var unscopedComputations = 0
var scopedComputations = 0
var synchronousComputations = 0

class UnscopedValue(val index: Int)

class ScopedValue(val index: Int)

class NullableValue

class SynchronousValue(val index: Int)

@Inject
@SingleIn(AppScope::class)
class Consumer(
  val providerOfLazy: Provider<SuspendLazy<UnscopedValue>>,
  val functionOfFunction: () -> suspend () -> UnscopedValue,
  val lazyOfFunction: SuspendLazy<suspend () -> UnscopedValue>,
  val functionOfLazy: suspend () -> SuspendLazy<UnscopedValue>,
  val lazyOfLazy: SuspendLazy<SuspendLazy<UnscopedValue>>,
  val lazyOfSuspendFunction: Lazy<suspend () -> UnscopedValue>,
  val deep: () -> Lazy<SuspendLazy<suspend () -> UnscopedValue>>,
)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val consumer: Consumer

  val providerOfLazy: Provider<SuspendLazy<UnscopedValue>>

  val functionOfFunction: () -> suspend () -> UnscopedValue

  val lazyOfFunction: SuspendLazy<suspend () -> UnscopedValue>

  val lazyOfSuspendProvider: SuspendLazy<SuspendProvider<UnscopedValue>>

  val deep: () -> Lazy<SuspendLazy<suspend () -> UnscopedValue>>

  val providerOfLazyMap: Provider<SuspendLazy<Map<String, suspend () -> UnscopedValue>>>

  val scopedProviderOfLazy: Provider<SuspendLazy<ScopedValue>>

  val scopedFunctionOfFunction: () -> suspend () -> ScopedValue

  val scopedLazyOfFunction: SuspendLazy<suspend () -> ScopedValue>

  val nullableProviderOfLazy: Provider<SuspendLazy<NullableValue?>>

  val suspendFunctionOfProvider: suspend () -> Provider<SynchronousValue>

  val suspendLazyOfProvider: SuspendLazy<Provider<SynchronousValue>>

  val suspendFunctionOfLazy: suspend () -> Lazy<SynchronousValue>

  val suspendFunctionOfFunction: suspend () -> () -> SynchronousValue

  val providerOfSuspendLazyOfProvider: Provider<SuspendLazy<Provider<SynchronousValue>>>

  @Provides
  suspend fun provideUnscopedValue(): UnscopedValue {
    unscopedComputations++
    return UnscopedValue(unscopedComputations)
  }

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideScopedValue(): ScopedValue {
    scopedComputations++
    return ScopedValue(scopedComputations)
  }

  @Provides suspend fun provideNullableValue(): NullableValue? = null

  @Provides
  fun provideSynchronousValue(): SynchronousValue {
    synchronousComputations++
    return SynchronousValue(synchronousComputations)
  }

  @Provides
  @IntoMap
  @StringKey("nested")
  suspend fun provideNestedMapValue(): UnscopedValue {
    unscopedComputations++
    return UnscopedValue(unscopedComputations)
  }

}

fun box(): String =
  runBlocking {
    val graph = createGraph<ExampleGraph>()

    unscopedComputations = 0
    val firstLazy = graph.providerOfLazy()
    val secondLazy = graph.providerOfLazy()
    assertEquals(0, unscopedComputations)
    assertEquals(1, firstLazy.value().index)
    assertEquals(1, firstLazy.value().index)
    assertEquals(1, unscopedComputations)
    assertEquals(2, secondLazy.value().index)
    assertEquals(2, unscopedComputations)

    unscopedComputations = 0
    val innerFunction: suspend () -> UnscopedValue = graph.functionOfFunction()
    assertEquals(0, unscopedComputations)
    assertEquals(1, innerFunction().index)
    assertEquals(2, innerFunction().index)

    unscopedComputations = 0
    val lazyOfFunction = graph.lazyOfFunction
    val cachedFunction: suspend () -> UnscopedValue = lazyOfFunction.value()
    val sameCachedFunction: suspend () -> UnscopedValue = lazyOfFunction.value()
    assertSame(cachedFunction, sameCachedFunction)
    assertEquals(0, unscopedComputations)
    assertEquals(1, cachedFunction().index)
    assertEquals(2, cachedFunction().index)

    unscopedComputations = 0
    val lazyOfSuspendProvider = graph.lazyOfSuspendProvider
    val cachedProvider: SuspendProvider<UnscopedValue> = lazyOfSuspendProvider.value()
    assertSame(cachedProvider, lazyOfSuspendProvider.value())
    assertEquals(0, unscopedComputations)
    assertEquals(1, cachedProvider.invoke().index)
    assertEquals(2, cachedProvider.invoke().index)

    unscopedComputations = 0
    val deepLazy = graph.deep()
    val deepSuspendLazy = deepLazy.value
    assertSame(deepSuspendLazy, deepLazy.value)
    val deepFunction: suspend () -> UnscopedValue = deepSuspendLazy.value()
    assertSame(deepFunction, deepSuspendLazy.value())
    assertEquals(0, unscopedComputations)
    assertEquals(1, deepFunction().index)
    assertEquals(2, deepFunction().index)

    unscopedComputations = 0
    val lazyMap = graph.providerOfLazyMap()
    val map = lazyMap.value()
    assertSame(map, lazyMap.value())
    val mapFunction: suspend () -> UnscopedValue = map.getValue("nested")
    assertEquals(0, unscopedComputations)
    assertEquals(1, mapFunction().index)
    assertEquals(2, mapFunction().index)

    unscopedComputations = 0
    val consumer = graph.consumer
    assertEquals(1, consumer.providerOfLazy().value().index)
    assertEquals(2, consumer.functionOfFunction()().index)
    assertEquals(3, consumer.lazyOfFunction.value()().index)
    assertEquals(4, consumer.functionOfLazy().value().index)
    assertEquals(5, consumer.lazyOfLazy.value().value().index)
    assertEquals(6, consumer.lazyOfSuspendFunction.value().index)
    assertEquals(7, consumer.deep().value.value()().index)

    scopedComputations = 0
    assertEquals(1, graph.scopedProviderOfLazy().value().index)
    assertEquals(1, graph.scopedProviderOfLazy().value().index)
    assertEquals(1, graph.scopedFunctionOfFunction()().index)
    assertEquals(1, graph.scopedLazyOfFunction.value()().index)
    assertEquals(1, scopedComputations)

    assertNull(graph.nullableProviderOfLazy().value())

    synchronousComputations = 0
    val synchronousProvider: Provider<SynchronousValue> = graph.suspendFunctionOfProvider()
    assertEquals(0, synchronousComputations)
    assertEquals(1, synchronousProvider().index)
    assertEquals(2, synchronousProvider().index)

    synchronousComputations = 0
    val suspendLazyOfProvider = graph.suspendLazyOfProvider
    val cachedSynchronousProvider: Provider<SynchronousValue> = suspendLazyOfProvider.value()
    assertSame(cachedSynchronousProvider, suspendLazyOfProvider.value())
    assertEquals(0, synchronousComputations)
    assertEquals(1, cachedSynchronousProvider().index)
    assertEquals(2, cachedSynchronousProvider().index)

    synchronousComputations = 0
    val firstSynchronousLazy: Lazy<SynchronousValue> = graph.suspendFunctionOfLazy()
    val secondSynchronousLazy: Lazy<SynchronousValue> = graph.suspendFunctionOfLazy()
    assertEquals(0, synchronousComputations)
    assertEquals(1, firstSynchronousLazy.value.index)
    assertEquals(1, firstSynchronousLazy.value.index)
    assertEquals(2, secondSynchronousLazy.value.index)

    synchronousComputations = 0
    val firstSynchronousFunction: () -> SynchronousValue = graph.suspendFunctionOfFunction()
    val secondSynchronousFunction: () -> SynchronousValue = graph.suspendFunctionOfFunction()
    assertEquals(0, synchronousComputations)
    assertEquals(1, firstSynchronousFunction().index)
    assertEquals(2, firstSynchronousFunction().index)
    assertEquals(3, secondSynchronousFunction().index)

    synchronousComputations = 0
    val nestedSynchronousLazy = graph.providerOfSuspendLazyOfProvider()
    val nestedSynchronousProvider: Provider<SynchronousValue> = nestedSynchronousLazy.value()
    assertSame(nestedSynchronousProvider, nestedSynchronousLazy.value())
    assertEquals(0, synchronousComputations)
    assertEquals(1, nestedSynchronousProvider().index)
    assertEquals(2, nestedSynchronousProvider().index)

    "OK"
  }
