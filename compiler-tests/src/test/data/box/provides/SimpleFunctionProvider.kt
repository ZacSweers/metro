@DependencyGraph
interface ExampleGraph {
  val provider: Provider<String>

//  @Provides
//  fun provideValue(): String = "Hello, world!"
}

fun box(): String {
  var provider = createGraph<ExampleGraph>().provider
  assertEquals(provider.invoke(), "Hello, world!")
  assertEquals(provider.invoke(), "Hello, world!")
  return "OK"
}
