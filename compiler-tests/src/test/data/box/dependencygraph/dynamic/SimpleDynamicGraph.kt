@DependencyGraph
interface AppGraph {
  val int: Int

  @Provides fun provideInt(): Int = 3
}

class Example {
  fun someTest(): Int {
    val testGraph = createGraph<AppGraph>(TestIntProvider())
    return testGraph.int
  }

  class TestIntProvider {
    @Provides fun provideInt(): Int = 2
  }
}

fun box(): String {
  assertEquals(2, Example().someTest())
  return "OK"
}