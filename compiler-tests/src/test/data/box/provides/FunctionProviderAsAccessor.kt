// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
abstract class ExampleGraph {
  var counter = 0

  abstract val stringProvider: () -> String
  abstract val intProvider: () -> Int

  @Provides fun provideString(): String = "Hello, world!"

  @Provides fun provideInt(): Int = counter++
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("Hello, world!", graph.stringProvider())
  assertEquals("Hello, world!", graph.stringProvider())
  assertEquals(0, graph.intProvider())
  assertEquals(1, graph.intProvider())
  return "OK"
}
