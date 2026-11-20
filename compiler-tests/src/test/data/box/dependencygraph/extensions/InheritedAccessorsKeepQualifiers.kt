// ENABLE_FULL_BINDING_GRAPH_VALIDATION

@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph : ChildGraph.Factory {
  @Provides fun provideInt(): Int = 1
  @Provides @Named("int") fun provideQualifiedInt(): Int = 2
  @Provides @SingleIn(AppScope::class) fun provideScopedLong(): Long = 3L
  @Provides @SingleIn(AppScope::class) @Named("long") fun provideScopedQualifiedLong(): Long = 4L
}

@GraphExtension
interface ChildGraph {
  val int: Int
  @Named("int") val qualifiedInt: Int
  val scopedLong: Long
  @Named("long") val qualifiedScopedLong: Long

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val child = createGraph<ParentGraph>().create()
  assertEquals(1, child.int)
  assertEquals(2, child.qualifiedInt)
  assertEquals(3L, child.scopedLong)
  assertEquals(4L, child.qualifiedScopedLong)
  return "OK"
}
