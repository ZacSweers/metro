package test

interface HasCompanion {
  companion object {
    @Provides fun provideString(): String = "companion"
  }
}

@DependencyGraph
interface ParentGraph : HasCompanion, ChildGraph.Factory {
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
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.create()
  assertEquals("companion", childGraph.string)
  assertEquals(1, childGraph.int)
  return "OK"
}
