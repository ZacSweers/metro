@DependencyGraph
interface AppGraph {
  val int: Int

  // By default this provides 3
  @Provides fun provideInt(): Int = 3
}

class Example {
  val propGraph = createGraph<AppGraph>(TestIntProvider(4))

  fun someTest(value: Int): Int {
    // Graph in a class
    val testGraph = createGraph<AppGraph>(TestIntProvider(value))
    return testGraph.int
  }
}

@BindingContainer
class TestIntProvider(private val value: Int) {
  @Provides fun provideInt(): Int = value
}

// top-level function-only
fun example() {
  check(5 == createGraph<AppGraph>(TestIntProvider(5)).int)
}
