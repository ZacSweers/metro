// MIN_COMPILER_VERSION: 2.5.0-Beta1

// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
@BindingContainer
class TypedBindings<T>(private val input: T) {
  @Provides fun value(): T = input

  companion {
    @Provides @Named("static") private fun text(): String = "constant"
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt
@DependencyGraph
interface Graph {
  val value: Int
  @get:Named("static") val text: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: TypedBindings<Int>): Graph
  }
}

fun box(): String {
  val graph = createGraphFactory<Graph.Factory>().create(TypedBindings(42))
  assertEquals(42, graph.value)
  assertEquals("constant", graph.text)
  return "OK"
}
