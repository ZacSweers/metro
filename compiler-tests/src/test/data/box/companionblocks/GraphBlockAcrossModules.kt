// MIN_COMPILER_VERSION: 2.5.0-Beta1
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
@DependencyGraph
interface AppGraph {
  companion { @Provides fun text(): String = "OK" }
  val text: String
}
@DependencyGraph
interface InputGraph {
  val text: String
  @DependencyGraph.Factory fun interface Factory {
    operator fun invoke(@Provides text: String): InputGraph
  }
}
@DependencyGraph
interface SingletonGraph {
  val number: Int
  @Provides fun number(): Int = 42
  companion object : SingletonGraph by createGraph()
}
// MODULE: main(lib)
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Main.kt
fun box(): String {
  assertEquals("OK", AppGraph().text)
  assertEquals(42, SingletonGraph.number)
  assertEquals(42, createGraph<SingletonGraph>().number)
  assertEquals("OK", createGraph<AppGraph>().text)
  assertEquals("input", InputGraph("input").text)
  assertEquals("factory", createGraphFactory<InputGraph.Factory>()("factory").text)
  return "OK"
}
