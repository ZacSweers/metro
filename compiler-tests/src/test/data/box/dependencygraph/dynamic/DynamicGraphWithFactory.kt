@DependencyGraph
interface AppGraph {
  val int: Int

  // By default this provides 3
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: Int): AppGraph
  }
}

class Example {
  val propGraph = createDynamicGraphFactory<AppGraph.Factory>(TestIntProvider(4)).create(3)

  fun someTest(value: Int): Int {
    // Graph in a class
    val testGraph = createDynamicGraphFactory<AppGraph.Factory>(TestIntProvider(value)).create(3)
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
  assertEquals(5, createDynamicGraphFactory<AppGraph.Factory>(TestIntProvider(5)).create(3).int)
  return "OK"
}