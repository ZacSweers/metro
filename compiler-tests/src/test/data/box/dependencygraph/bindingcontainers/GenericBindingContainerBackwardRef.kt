// Generic binding container with backward-referencing type parameters (<T, R : T>)
@BindingContainer
class HierarchyBindings<T, R : T>(private val base: T, private val derived: R) {
  @Provides fun provideBase(): T = base
  @Provides fun provideDerived(): R = derived
}

@DependencyGraph
interface AppGraph {
  val base: Number
  val derived: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: HierarchyBindings<Number, Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(HierarchyBindings(3.14 as Number, 42))
  assertEquals(3.14, graph.base)
  assertEquals(42, graph.derived)
  return "OK"
}
