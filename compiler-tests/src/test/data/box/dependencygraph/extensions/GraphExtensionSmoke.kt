package test

@DependencyGraph
interface ParentGraph : ChildGraph.Factory {
  @Provides fun provideInt(): Int = 1
}

@GraphExtension
interface ChildGraph {
  val int: Int

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.create()
  assertEquals(1, childGraph.int)
  return "OK"
}
