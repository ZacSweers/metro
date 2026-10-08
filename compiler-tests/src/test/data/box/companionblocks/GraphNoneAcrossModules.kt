// MIN_COMPILER_VERSION: 2.5.0-Beta1
// MODULE: lib
// COMPANION_MODE: NONE
// FILE: Library.kt
@DependencyGraph
interface AppGraph {
  companion object { @Provides fun text(): String = "OK" }
  val text: String
}
@DependencyGraph
interface InputGraph {
  val text: String
  @DependencyGraph.Factory fun interface Factory {
    fun create(@Provides text: String): InputGraph
  }
}
// MODULE: main(lib)
// COMPANION_MODE: COMPANION_OBJECT
// FILE: Main.kt
fun box(): String {
  assertEquals("OK", createGraph<AppGraph>().text)
  assertEquals("factory", createGraphFactory<InputGraph.Factory>().create("factory").text)
  return "OK"
}
