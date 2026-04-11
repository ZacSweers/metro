// When extending graphs, parent accessors whose type matches the child graph's
// own type (or its factory) should be ignored. This prevents self-referential
// binding conflicts when the parent exposes provider fields for its own type.
// https://github.com/ZacSweers/metro/pull/883

@DependencyGraph
interface ParentGraph {
  @Provides fun provideInt(): Int = 3

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val int: Int

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childGraphFactory().create()
  assertEquals(3, child.int)
  return "OK"
}
