package test

interface Base
class Impl : Base

@DependencyGraph
interface ParentGraph : ChildGraph.Factory {
  @Provides fun impl(): Impl = Impl()
  @Binds fun bind(impl: Impl): Base
}

@GraphExtension
interface ChildGraph {
  val base: Base

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.create()
  assertEquals(Impl::class, childGraph.base::class)
  return "OK"
}
