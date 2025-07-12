@DependencyGraph
interface AppGraph {
  val ints: Set<Int>

  @Provides fun provideInt(): Int = 3

  @Binds @IntoSet val Int.bind: Int
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf(3), graph.ints)
  return "OK"
}