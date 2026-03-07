// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
abstract class ExampleGraph {
  var counter = 0

  abstract val lazyInt: Lazy<Int>
  abstract val functionProvider: () -> Int
  abstract val consumer: MixedConsumer

  @Provides fun provideInt(): Int = counter++
}

@Inject class MixedConsumer(val lazyInt: Lazy<Int>, val intProvider: () -> Int)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Function provider should return new values each call
  assertEquals(0, graph.functionProvider())
  assertEquals(1, graph.functionProvider())
  // Lazy should be cached once captured
  val lazyInt = graph.lazyInt
  assertEquals(2, lazyInt.value)
  assertEquals(2, lazyInt.value)
  // Consumer should have independent instances
  val consumer = graph.consumer
  assertEquals(3, consumer.intProvider())
  assertEquals(4, consumer.intProvider())
  val consumerLazy = consumer.lazyInt
  assertEquals(5, consumerLazy.value)
  assertEquals(5, consumerLazy.value)
  return "OK"
}
