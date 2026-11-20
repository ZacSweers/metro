@DependencyGraph(Unit::class)
interface ExampleGraph {
  val nullableInts: Map<Int, Int?>
  val ints: Map<Int, Int>

  @Provides
  @IntoMap
  @IntKey(0)
  fun provideInt1(): Int? = 0

  @Provides
  @IntoMap
  @IntKey(1)
  fun provideIntNull(): Int? = null

  @Provides
  @IntoMap
  @IntKey(2)
  fun provideIntNull2(): Int? = null

  @Provides
  @IntoMap
  @IntKey(3)
  fun provideInt3(): Int = 3
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(mapOf(3 to 3), graph.ints)
  assertEquals(mapOf(0 to 0, 1 to null, 2 to null), graph.nullableInts)
  return "OK"
}
