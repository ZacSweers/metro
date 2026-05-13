// Test for Map<K, () -> Lazy<V>> multibindings
@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0

  @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1

  @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2

  // Map with () -> Lazy values
  val providerLazyInts: Map<Int, () -> Lazy<Int>>

  // Provider wrapping map with () -> Lazy values
  val providerOfProviderLazyInts: () -> Map<Int, () -> Lazy<Int>>

  // Class that injects the provider lazy map
  val consumer: ProviderLazyMapConsumer
}

@Inject class ProviderLazyMapConsumer(val providerLazyMap: Map<Int, () -> Lazy<Int>>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test Map<Int, () -> Lazy<Int>>
  val providerLazyInts = graph.providerLazyInts
  assertEquals(
    mapOf(0 to 0, 1 to 1, 2 to 2),
    providerLazyInts.mapValues { (_, provider) -> provider().value },
  )

  // Test () -> Map<Int, () -> Lazy<Int>>
  val providerOfProviderLazyInts = graph.providerOfProviderLazyInts
  assertEquals(
    mapOf(0 to 0, 1 to 1, 2 to 2),
    providerOfProviderLazyInts().mapValues { (_, provider) -> provider().value },
  )

  // Test injected class
  val consumer = graph.consumer
  assertEquals(
    mapOf(0 to 0, 1 to 1, 2 to 2),
    consumer.providerLazyMap.mapValues { (_, provider) -> provider().value },
  )

  return "OK"
}
