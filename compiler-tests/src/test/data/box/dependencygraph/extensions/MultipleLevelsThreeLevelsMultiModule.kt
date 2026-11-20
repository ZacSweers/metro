// MODULE: lib
// FILE: Library.kt
package test

@GraphExtension
interface ChildGraph {
  val string: String
  val int: Int

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension
interface ParentGraph : ChildGraph.Factory {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ParentGraph
  }

  @Provides fun provideInt(): Int = 1
}

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph
interface GrandParentGraph : ParentGraph.Factory {
  @Provides fun provideString(): String = "grandparent"
}

fun box(): String {
  val grandParentGraph = createGraph<GrandParentGraph>()
  val parentGraph = grandParentGraph.create()
  val childGraph = parentGraph.create()
  assertEquals("grandparent", childGraph.string)
  assertEquals(1, childGraph.int)
  return "OK"
}
