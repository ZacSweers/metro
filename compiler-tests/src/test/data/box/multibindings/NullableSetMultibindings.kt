@DependencyGraph(Unit::class)
interface ExampleGraph {
  val nullableInts: Set<Int?>
  val ints: Set<Int>

  @Provides
  @IntoSet
  fun provideInt1(): Int? = 0

  @Provides
  @IntoSet
  fun provideIntNull(): Int? = null

  @Provides
  @IntoSet
  fun provideIntNull2(): Int? = null

  @Provides
  @IntoSet
  fun provideInt3(): Int = 3
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(setOf(3), graph.ints)
  assertEquals(setOf(0, null), graph.nullableInts)
  return "OK"
}
