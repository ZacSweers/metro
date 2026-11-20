// MODULE: lib
// FILE: Library.kt
package test

@GraphExtension
interface ChildGraph {
  val int: Int

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph
interface ParentGraph : ChildGraph.Factory {
  @Provides fun provideInt(): Int = 1
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.create()
  assertEquals(1, childGraph.int)
  return "OK"
}
