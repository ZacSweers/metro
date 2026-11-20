// Regression test for https://github.com/ZacSweers/metro/issues/375

@GraphExtension
interface ChildGraph {
  val int: Int

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

@SingleIn(AppScope::class)
@DependencyGraph
interface ParentGraph : ChildGraph.Factory {
  @Provides fun provideInt(): Int = 1
}

fun box(): String {
  assertEquals(1, createGraph<ParentGraph>().create().int)
  return "OK"
}
