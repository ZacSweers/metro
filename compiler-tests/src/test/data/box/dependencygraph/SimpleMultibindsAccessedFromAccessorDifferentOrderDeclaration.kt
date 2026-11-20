package test

@DependencyGraph
interface ExampleGraph {
  @Provides
  @IntoSet
  fun provideString(): String = "Hello, world!"

  @Multibinds val strings: Set<String>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val strings = graph.strings
  assertEquals(setOf("Hello, world!"), strings)
  return "OK"
}
