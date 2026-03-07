// Test for empty Map<K, Lazy<V>> and Map<K, Provider<Lazy<V>>> multibindings
@DependencyGraph
interface ExampleGraph {
  @Multibinds(allowEmpty = true) val ints: Map<Int, Int>

  // Empty map with Lazy values
  val lazyInts: Map<Int, Lazy<Int>>

  // Empty map with Provider<Lazy> values
  val providerLazyInts: Map<Int, Provider<Lazy<Int>>>

  // Provider wrapping empty map with Lazy values
  val providerOfLazyInts: Provider<Map<Int, Lazy<Int>>>

  // Provider wrapping empty map with Provider<Lazy> values
  val providerOfProviderLazyInts: Provider<Map<Int, Provider<Lazy<Int>>>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test empty Map<Int, Lazy<Int>>
  val lazyInts = graph.lazyInts
  assertTrue(lazyInts.isEmpty())

  // Test empty Map<Int, Provider<Lazy<Int>>>
  val providerLazyInts = graph.providerLazyInts
  assertTrue(providerLazyInts.isEmpty())

  // Test Provider<Map<Int, Lazy<Int>>>
  val providerOfLazyInts = graph.providerOfLazyInts
  assertTrue(providerOfLazyInts().isEmpty())

  // Test Provider<Map<Int, Provider<Lazy<Int>>>>
  val providerOfProviderLazyInts = graph.providerOfProviderLazyInts
  assertTrue(providerOfProviderLazyInts().isEmpty())

  return "OK"
}
