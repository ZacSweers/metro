// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// MODULE: base
// FILE: BaseFactory.kt
interface BinaryFactory<R> {
  fun build(@Provides value: String = "binary default"): R
}
// MODULE: lib(base)
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true
// FILE: Library.kt
@DependencyGraph
interface InheritedGraph {
  val value: String
  @DependencyGraph.Factory interface Factory : BinaryFactory<InheritedGraph>
}

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
// MODULE: main(lib, base)
// FILE: Main.kt
fun box(): String {
  assertEquals("binary default", createGraphFactory<InheritedGraph.Factory>().build().value)
  assertEquals("explicit", InheritedGraph.build("explicit").value)
  val graph = AppGraph.build()
  assertEquals("default", graph.text)
  assertEquals("default-suffix", graph.suffix)
  val changed = AppGraph.build("custom")
  assertEquals("custom-suffix", changed.suffix)
  return "OK"
}
