package test

@DependencyGraph
interface GrandParentGraph : ParentGraph.Factory {
  @Provides fun provideString(): String = "grandparent"
}

@GraphExtension
interface ParentGraph : ChildGraph.Factory {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ParentGraph
  }

  @Provides fun provideInt(): Int = 1
}

@GraphExtension
interface ChildGraph {
  val string: String
  val int: Int

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val grandParentGraph = createGraph<GrandParentGraph>()
  val parentGraph = grandParentGraph.create()
  val childGraph = parentGraph.create()
  assertEquals("grandparent", childGraph.string)
  assertEquals(1, childGraph.int)
  return "OK"
}
