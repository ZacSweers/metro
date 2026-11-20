@DependencyGraph
abstract class ExampleGraph {
  private var counter = 0

  abstract val scalar: Int
  abstract val provider: () -> Int
  abstract val lazy: Lazy<Int>
  abstract val providerOfLazy: () -> Lazy<Int>

  @Provides fun provideInt(): Int = counter++
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(0, graph.scalar)
  assertEquals(1, graph.provider())

  val lazy = graph.lazy
  assertEquals(2, lazy.value)
  assertEquals(2, lazy.value)

  val providedLazy = graph.providerOfLazy()
  assertEquals(3, providedLazy.value)
  assertEquals(3, providedLazy.value)
  return "OK"
}
