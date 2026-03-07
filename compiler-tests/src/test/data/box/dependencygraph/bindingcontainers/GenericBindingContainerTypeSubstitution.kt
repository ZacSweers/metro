// Generic binding container where type param IS the return type (type substitution)
@BindingContainer
class ValueBindings<T>(private val value: T) {
  @Provides fun provideValue(): T = value
}

@DependencyGraph
interface AppGraph {
  val value: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: ValueBindings<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(ValueBindings(42))
  assertEquals(42, graph.value)
  return "OK"
}
