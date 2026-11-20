package test

@DependencyGraph
interface ExampleGraph {

  val value: String
  val value2: CharSequence

  @Provides
  fun bind(value: String): CharSequence = value

  @Provides
  fun provideValue(): String = "Hello, world!"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("Hello, world!", graph.value)
  assertEquals("Hello, world!", graph.value2)
  return "OK"
}
