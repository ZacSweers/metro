// The annotation is stored on the FirPropertyAccessorSymbol, this test ensures
// we check there too

@DependencyGraph
abstract class ExampleGraph {
  abstract val count: Int

  @get:Provides val countProvider: Int = 3
}

fun box(): String {
  assertEquals(3, createGraph<ExampleGraph>().count)
  return "OK"
}
