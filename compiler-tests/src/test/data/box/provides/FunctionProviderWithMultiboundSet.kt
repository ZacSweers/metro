// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
interface ExampleGraph {
  val strings: Set<String>
  val stringProvider: () -> Set<String>
  val consumer: SetConsumer

  @Provides @IntoSet fun provideFirst(): String = "a"

  @Provides @IntoSet fun provideSecond(): String = "b"
}

@Inject class SetConsumer(val stringProvider: () -> Set<String>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val expected = setOf("a", "b")
  assertEquals(expected, graph.strings)
  assertEquals(expected, graph.stringProvider())
  assertEquals(expected, graph.consumer.stringProvider())
  return "OK"
}
