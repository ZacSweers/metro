@DependencyGraph(Unit::class)
interface ExampleGraph {
  val int: Int
  val nullable: Int?

  @Provides
  fun provideInt(): Int = 0

  @Provides
  fun provideNullableInt(): Int? = 1
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(0, graph.int)
  assertEquals(1, graph.nullable)
  return "OK"
}
