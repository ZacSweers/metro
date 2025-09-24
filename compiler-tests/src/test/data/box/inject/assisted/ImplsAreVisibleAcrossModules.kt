// MODULE: lib
@AssistedInject
class Example(@Assisted val input: String) {
  @AssistedFactory
  interface Factory {
    fun create(input: String): Example
  }
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph {
  val factory: Example.Factory
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val instance = graph.factory.create("Hello")
  assertEquals("Hello", instance.input)
  return "OK"
}