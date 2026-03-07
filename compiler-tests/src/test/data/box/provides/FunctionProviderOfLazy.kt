// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
abstract class ExampleGraph {
  var counter = 0

  abstract val lazyProvider: () -> Lazy<Int>
  abstract val consumer: LazyProviderConsumer

  @Provides fun provideInt(): Int = counter++
}

@Inject class LazyProviderConsumer(val lazyProvider: () -> Lazy<Int>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Each call to the function provider should return a new Lazy
  val lazy1 = graph.lazyProvider()
  val lazy2 = graph.lazyProvider()
  // Each Lazy caches its own value
  assertEquals(0, lazy1.value)
  assertEquals(0, lazy1.value)
  assertEquals(1, lazy2.value)
  assertEquals(1, lazy2.value)
  // Injected consumer should behave the same
  val consumer = graph.consumer
  val lazy3 = consumer.lazyProvider()
  assertEquals(2, lazy3.value)
  assertEquals(2, lazy3.value)
  return "OK"
}
