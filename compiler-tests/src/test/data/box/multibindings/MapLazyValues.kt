// Test for Map<K, Lazy<V>> multibindings
@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0

  @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1

  @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2

  // Map with Lazy values
  val lazyInts: Map<Int, Lazy<Int>>

  // Provider wrapping map with Lazy values
  val providerOfLazyInts: Provider<Map<Int, Lazy<Int>>>

  // Class that injects the lazy map
  val consumer: LazyMapConsumer
}

@Inject class LazyMapConsumer(val lazyMap: Map<Int, Lazy<Int>>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test Map<Int, Lazy<Int>>
  val lazyInts = graph.lazyInts
  assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), lazyInts.mapValues { (_, lazy) -> lazy.value })

  // Test Provider<Map<Int, Lazy<Int>>>
  val providerOfLazyInts = graph.providerOfLazyInts
  assertEquals(
    mapOf(0 to 0, 1 to 1, 2 to 2),
    providerOfLazyInts().mapValues { (_, lazy) -> lazy.value },
  )

  // Test injected class
  val consumer = graph.consumer
  assertEquals(
    mapOf(0 to 0, 1 to 1, 2 to 2),
    consumer.lazyMap.mapValues { (_, lazy) -> lazy.value },
  )

  return "OK"
}
