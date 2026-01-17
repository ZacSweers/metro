// Test for Map<K, Provider<Lazy<V>>> multibindings
@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0

  @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1

  @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2

  // Map with Provider<Lazy> values
  val providerLazyInts: Map<Int, Provider<Lazy<Int>>>

  // Provider wrapping map with Provider<Lazy> values
  val providerOfProviderLazyInts: Provider<Map<Int, Provider<Lazy<Int>>>>

  // Class that injects the provider lazy map
  val consumer: ProviderLazyMapConsumer
}

@Inject class ProviderLazyMapConsumer(val providerLazyMap: Map<Int, Provider<Lazy<Int>>>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test Map<Int, Provider<Lazy<Int>>>
  val providerLazyInts = graph.providerLazyInts
  assertEquals(
    mapOf(0 to 0, 1 to 1, 2 to 2),
    providerLazyInts.mapValues { (_, provider) -> provider().value },
  )

  // Test Provider<Map<Int, Provider<Lazy<Int>>>>
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
