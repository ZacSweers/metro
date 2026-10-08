// MIN_COMPILER_VERSION: 2.5.0-Beta1
// GENERATE_CLASSES_IN_IR: false
// OMIT_REDUNDANT_MIRRORS: false
// ENABLE_PRIVATE_PROVIDER_PROPERTIES

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
    private val providedLong: Long
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
  val short: Short

  // Private getters require the generated helper in this compilation.
  @get:Provides
  private val providedShort: Short
    get() = 7
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("mirror", graph.value.text)
  assertEquals(25L, graph.long)
  assertEquals(7.toShort(), graph.short)
  return "OK"
}
