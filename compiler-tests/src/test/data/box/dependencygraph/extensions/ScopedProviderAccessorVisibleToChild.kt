// Regression test

// MODULE: lib
// FILE: Source1.kt

interface Base

class Impl : Base

@GraphExtension
interface ChildGraph {
  val message: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
// FILE: Source2.kt

@DependencyGraph(Unit::class)
interface ParentGraph {
  val base: Base

  fun childGraphFactory(): ChildGraph.Factory

  @Provides
  @SingleIn(Unit::class)
  fun provideBase(): Base = Impl()

  @Provides
  fun provideMessage(base: Base): String = base.toString()
}

fun box(): String {
  val graph = createGraph<ParentGraph>()
  assertSame(graph.base, graph.base)
  assertEquals(graph.base.toString(), graph.childGraphFactory().create().message)
  return "OK"
}
