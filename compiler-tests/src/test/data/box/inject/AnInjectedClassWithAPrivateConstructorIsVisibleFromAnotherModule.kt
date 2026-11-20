// MODULE: lib
// FILE: Library.kt
package test

@Inject
class ExampleClass private constructor(private val value: Int) {
  fun call(): Int = value
}

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides int: Int): ExampleGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(2)
  assertEquals(2, graph.exampleClass.call())
  return "OK"
}
