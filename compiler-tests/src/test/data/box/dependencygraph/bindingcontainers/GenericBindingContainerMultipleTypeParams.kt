// Generic binding container with multiple type parameters
@BindingContainer
class PairBindings<A, B>(private val first: A, private val second: B) {
  @Provides fun provideFirst(): A = first
  @Provides fun provideSecond(): B = second
  @Provides fun providePair(): Pair<A, B> = Pair(first, second)
}

@DependencyGraph
interface AppGraph {
  val first: String
  val second: Int
  val pair: Pair<String, Int>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: PairBindings<String, Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(PairBindings("hello", 42))
  assertEquals("hello", graph.first)
  assertEquals(42, graph.second)
  assertEquals(Pair("hello", 42), graph.pair)
  return "OK"
}
