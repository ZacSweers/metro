// https://github.com/ZacSweers/metro/issues/2506
// Regression test for graph factory access across modules with IR-only class
// generation. The upstream module publishes AppGraph.Factory.Impl in metadata; downstream
// createGraphFactory<AppGraph.Factory>() should still use the companion-backed interface factory
// path instead of looking for a companion factory() accessor.
// This specifically is just for IR factory gen in 2.4.20+
// MODULE: lib
@DependencyGraph
interface AppGraph {
  val value: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: String): AppGraph
  }
}

// MODULE: main(lib)
fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create("hello")
  assertEquals("hello", graph.value)
  return "OK"
}
