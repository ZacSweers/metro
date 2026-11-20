@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides
  @IntoSet
  fun provideString(): String = "Hello, world!"
}

@Inject
class ExampleClass(val strings: Set<String>) {
  fun call(): Set<String> = strings
}

fun box(): String {
  val example = createGraph<ExampleGraph>().exampleClass
  assertEquals(setOf("Hello, world!"), example.call())
  return "OK"
}
