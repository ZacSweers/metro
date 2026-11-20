package test

@DependencyGraph
interface ParentGraph : ChildGraph.Factory {
  @IntoSet
  @Provides
  fun stringFromParent(): String = "parent"

  val parentSet: Set<String>
}

@GraphExtension
interface ChildGraph {
  val strings: Set<String>

  @IntoSet
  @Provides
  fun stringFromChild(): String = "child"

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.create()
  assertEquals(setOf("parent"), parentGraph.parentSet)
  assertEquals(setOf("parent", "child"), childGraph.strings)
  return "OK"
}
