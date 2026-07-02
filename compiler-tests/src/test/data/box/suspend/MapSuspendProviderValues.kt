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

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test Map<Int, SuspendProvider<Int>>
  val suspendProviderInts = graph.suspendProviderInts
  assertEquals(3, suspendProviderInts.size)
  assertTrue(suspendProviderInts.containsKey(0))
  assertTrue(suspendProviderInts.containsKey(1))
  assertTrue(suspendProviderInts.containsKey(2))

  // Test Provider<Map<Int, SuspendProvider<Int>>>
  val providerMap = graph.providerOfSuspendProviderInts()
  assertEquals(3, providerMap.size)

  return "OK"
}
