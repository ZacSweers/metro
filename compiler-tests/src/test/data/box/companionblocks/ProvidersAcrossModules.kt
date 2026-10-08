// MIN_COMPILER_VERSION: 2.5.0-Beta1

// MODULE: lib
@BindingContainer
interface LibraryProviders {
  companion {
    @Provides
    @Named("input")
    fun input(): String = "library"

    @Provides
    private fun length(@Named("input") input: String): Int = input.length

    @get:Provides
    val long: Long
      get() = 25L
  }
}

@BindingContainer
abstract class LibraryFields {
  companion {
    @Provides val double: Double = 2.5
  }
}

// MODULE: main(lib)
@DependencyGraph(bindingContainers = [LibraryProviders::class, LibraryFields::class])
interface Graph {
  @get:Named("input") val input: String
  val int: Int
  val long: Long
  val double: Double
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("library", graph.input)
  assertEquals(7, graph.int)
  assertEquals(25L, graph.long)
  assertEquals(2.5, graph.double)
  return "OK"
}
