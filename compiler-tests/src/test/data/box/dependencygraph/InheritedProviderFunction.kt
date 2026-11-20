@DependencyGraph
interface ExampleGraph : TextProvider {
  val value: String
}

interface TextProvider {
  @Provides
  fun provideValue(): String = "Hello, world!"
}

fun box(): String {
  assertEquals("Hello, world!", createGraph<ExampleGraph>().value)
  return "OK"
}
