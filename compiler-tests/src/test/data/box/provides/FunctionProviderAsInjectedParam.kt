// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
abstract class ExampleGraph {
  var counter = 0

  abstract val consumer: StringConsumer

  @Provides fun provideString(): String = "Hello, world!"

  @Provides fun provideInt(): Int = counter++
}

@Inject class StringConsumer(val stringProvider: () -> String, val intProvider: () -> Int)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val consumer = graph.consumer
  assertEquals("Hello, world!", consumer.stringProvider())
  assertEquals("Hello, world!", consumer.stringProvider())
  assertEquals(0, consumer.intProvider())
  assertEquals(1, consumer.intProvider())
  return "OK"
}
