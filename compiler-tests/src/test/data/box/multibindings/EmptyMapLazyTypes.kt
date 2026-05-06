// Test for empty Map<K, Lazy<V>> and Map<K, () -> Lazy<V>> multibindings
@DependencyGraph
interface ExampleGraph {
  @Multibinds(allowEmpty = true) val ints: Map<Int, Int>

  // Empty map with Lazy values
  val lazyInts: Map<Int, Lazy<Int>>

  // Empty map with () -> Lazy values
  val providerLazyInts: Map<Int, () -> Lazy<Int>>

  // Provider wrapping empty map with Lazy values
  val providerOfLazyInts: () -> Map<Int, Lazy<Int>>

  // Provider wrapping empty map with () -> Lazy values
  val providerOfProviderLazyInts: () -> Map<Int, () -> Lazy<Int>>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test empty Map<Int, Lazy<Int>>
  val lazyInts = graph.lazyInts
  assertTrue(lazyInts.isEmpty())

  // Test empty Map<Int, () -> Lazy<Int>>
  val providerLazyInts = graph.providerLazyInts
  assertTrue(providerLazyInts.isEmpty())

  // Test () -> Map<Int, Lazy<Int>>
  val providerOfLazyInts = graph.providerOfLazyInts
  assertTrue(providerOfLazyInts().isEmpty())

  // Test () -> Map<Int, () -> Lazy<Int>>
  val providerOfProviderLazyInts = graph.providerOfProviderLazyInts
  assertTrue(providerOfProviderLazyInts().isEmpty())

  return "OK"
}
