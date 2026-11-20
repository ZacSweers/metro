package test

@DependencyGraph
abstract class ParentGraph : ChildGraph.Factory {
  private var count: Int = 0

  @Provides fun provideInt(): Int = count++
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
  assertEquals(0, childGraph.int)
  assertEquals(1, childGraph.int)
  assertEquals(2, childGraph.int)
  return "OK"
}
