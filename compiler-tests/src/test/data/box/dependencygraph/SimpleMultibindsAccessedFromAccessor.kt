package test

@DependencyGraph
interface ExampleGraph {
  @Multibinds val strings: Set<String>

  @Provides
  @IntoSet
  fun provideString(): String = "Hello, world!"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val strings = graph.strings
  assertEquals(setOf("Hello, world!"), strings)
  return "OK"
}
