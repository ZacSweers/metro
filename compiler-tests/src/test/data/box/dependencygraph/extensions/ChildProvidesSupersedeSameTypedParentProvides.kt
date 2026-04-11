// A child graph extension that provides the same type as the parent should
// use the child's version, not the parent's. Previously Metro only ignored
// parent bindings if the binding was itself a graph type.
// https://github.com/ZacSweers/metro/pull/883

@DependencyGraph
interface ParentGraph {
  @Provides fun provideString(): String = "parent"

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String

  @Provides fun provideString(): String = "child"

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childGraphFactory().create()
  assertEquals("child", child.string)
  return "OK"
}
