// MODULE: lib
@DependencyGraph
interface ChildGraph {
  val value: Int

  @Provides
  fun provideInt(): Int = 3
}

// MODULE: main(lib)
@DependencyGraph
interface ParentGraph : ChildGraph

fun box(): String {
  val graph = createGraph<ParentGraph>()
  assertEquals(graph.value, 3)
  return "OK"
}
