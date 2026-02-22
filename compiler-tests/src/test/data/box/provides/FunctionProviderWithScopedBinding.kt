// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph(AppScope::class)
abstract class ExampleGraph {
  var counter = 0

  abstract val intProvider: () -> Int
  abstract val stringProvider: () -> String
  abstract val consumer: ScopedConsumer

  @SingleIn(AppScope::class) @Provides fun provideInt(): Int = counter++

  @Provides fun provideString(): String = "Hello"
}

@Inject class ScopedConsumer(val intProvider: () -> Int, val stringProvider: () -> String)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Scoped binding should always return the same value
  assertEquals(0, graph.intProvider())
  assertEquals(0, graph.intProvider())
  assertEquals(0, graph.intProvider())
  // Unscoped binding should return new values
  assertEquals("Hello", graph.stringProvider())
  // Injected consumer should also see the scoped value
  val consumer = graph.consumer
  assertEquals(0, consumer.intProvider())
  assertEquals(0, consumer.intProvider())
  assertEquals("Hello", consumer.stringProvider())
  return "OK"
}
