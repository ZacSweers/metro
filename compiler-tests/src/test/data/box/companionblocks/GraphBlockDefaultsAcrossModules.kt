// MIN_COMPILER_VERSION: 2.5.0-Beta1
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
@DependencyGraph
interface AppGraph {
  @get:Named("text") val text: String
  val suffix: String

  @DependencyGraph.Factory
  interface Factory {
    fun build(
      @Provides @Named("text") text: String = defaultText(),
      @Provides suffix: String = text + "-suffix",
    ): AppGraph

    fun defaultText(): String = "default"
  }
}
// MODULE: main(lib)
// FILE: Main.kt
fun box(): String {
  val graph = AppGraph.build()
  assertEquals("default", graph.text)
  assertEquals("default-suffix", graph.suffix)
  val changed = AppGraph.build("custom")
  assertEquals("custom-suffix", changed.suffix)
  return "OK"
}
