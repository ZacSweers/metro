// MODULE: lib
@DependencyGraph
interface AppGraph {
  val int: Int

  // By default this provides 3
  @Provides fun provideInt(): Int = 3
}

// MODULE: main(lib)
class Example {
  val propGraph = createDynamicGraph<AppGraph>(TestIntProvider(4))

  fun someTest(value: Int): Int {
    // Graph in a class
    val testGraph = createDynamicGraph<AppGraph>(TestIntProvider(value))
    return testGraph.int
  }
}

@BindingContainer
class TestIntProvider(private val value: Int) {
  @Provides fun provideInt(): Int = value
}

fun box(): String {
  assertEquals(2, Example().someTest(2))
  assertEquals(4, Example().propGraph.int)
  // top-level function-only
  assertEquals(5, createDynamicGraph<AppGraph>(TestIntProvider(5)).int)
  return "OK"
}