class NumberProviders {
  fun provideInt(): Int = 1
  @Named("int") fun provideQualifiedInt(): Int = 2
  @SingleIn(AppScope::class) fun provideScopedLong(): Long = 3L
  @SingleIn(AppScope::class) @Named("long") fun provideScopedQualifiedLong(): Long = 4L
}

@DependencyGraph
interface ExampleGraph {
  val int: Int
  @Named("int") val qualifiedInt: Int
  val scopedLong: Long
  @Named("long") val qualifiedScopedLong: Long

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes parent: NumberProviders): ExampleGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(NumberProviders())
  assertEquals(1, graph.int)
  assertEquals(2, graph.qualifiedInt)
  assertEquals(3L, graph.scopedLong)
  assertEquals(4L, graph.qualifiedScopedLong)
  return "OK"
}
