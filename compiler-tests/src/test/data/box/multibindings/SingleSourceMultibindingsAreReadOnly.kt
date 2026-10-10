@DependencyGraph
interface AppGraph {
  val ints: Set<Int>
  val strings: Map<String, Int>

  @Provides @ElementsIntoSet fun provideInts(): Set<Int> = setOf(1, 2, 3)

  @Provides @IntoMap @StringKey("one") fun provideOne(): Int = 1
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf(1, 2, 3), graph.ints)
  assertEquals(mapOf("one" to 1), graph.strings)
  assertFailsWith<UnsupportedOperationException> { (graph.ints as MutableSet<Int>).clear() }
  assertFailsWith<UnsupportedOperationException> {
    (graph.strings as MutableMap<String, Int>).clear()
  }
  return "OK"
}
