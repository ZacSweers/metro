// MIN_COMPILER_VERSION: 2.5.0-Beta1
// GENERATE_CLASSES_IN_IR: true
// OMIT_REDUNDANT_MIRRORS: true

// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
class Value(val text: String)

@BindingContainer
@ContributesTo(AppScope::class)
interface LibraryProviders {
  companion {
    @Provides
    fun value(@Named("text") text: String): Value = Value(text)

    @Provides
    @Named("text")
    val text: String
      get() = "creator"
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt
@DependencyGraph(AppScope::class)
interface Graph {
  val value: Value
}

fun box(): String {
  assertEquals("creator", createGraph<Graph>().value.text)
  return "OK"
}
