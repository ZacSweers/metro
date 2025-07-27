// https://github.com/ZacSweers/metro/issues/807
@DependencyGraph
interface Graph {
  @Multibinds(allowEmpty = true)
  val set1: Set<Int>

  @Named("s2")
  val set2: Set<Int>

  @Provides
  @IntoSet
  @Named("s2")
  val provideValue1: Int
    get() = 1

  @Provides
  @IntoSet
  @get:Named("s2")
  val provideValue2: Int
    get() = 2
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals(emptySet(), graph.set1)
  assertEquals(setOf(1, 2), graph.set2)
  return "OK"
}