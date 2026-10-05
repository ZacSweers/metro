// MIN_COMPILER_VERSION: 2.5.0-Beta1
// GENERATE_CLASSES_IN_IR: false
// OMIT_REDUNDANT_MIRRORS: false

// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
class Value(val text: String)

@BindingContainer
interface LibraryProviders {
  companion {
    @Provides
    fun value(@Named("text") text: String): Value = Value(text)

    @get:Provides
    val providedLong: Long
      get() = 25L
  }
}

@BindingContainer
abstract class LibraryBackedProviders {
  companion {
    @Provides
    @Named("text")
    val text: String = "mirror"
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt
@DependencyGraph(bindingContainers = [LibraryProviders::class, LibraryBackedProviders::class])
interface Graph {
  val value: Value
  val long: Long
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("mirror", graph.value.text)
  assertEquals(25L, graph.long)
  return "OK"
}
