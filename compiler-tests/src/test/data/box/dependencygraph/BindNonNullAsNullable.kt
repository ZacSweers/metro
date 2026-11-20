@DependencyGraph(Unit::class)
interface ExampleGraph {
  val int: Int
  val nullableInt: Int?

  @Provides
  fun provideInt(): Int = 1

  @Binds
  val Int.bindAsNullable: Int?
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(1, graph.int)
  assertEquals(1, graph.nullableInt)
  return "OK"
}
