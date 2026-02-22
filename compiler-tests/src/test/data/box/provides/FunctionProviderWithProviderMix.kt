// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
abstract class ExampleGraph {
  var counter = 0

  abstract val metroProvider: Provider<Int>
  abstract val functionProvider: () -> Int

  @Provides fun provideInt(): Int = counter++
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Both should work as providers, each call increments counter
  assertEquals(0, graph.metroProvider())
  assertEquals(1, graph.functionProvider())
  assertEquals(2, graph.metroProvider())
  assertEquals(3, graph.functionProvider())
  return "OK"
}
