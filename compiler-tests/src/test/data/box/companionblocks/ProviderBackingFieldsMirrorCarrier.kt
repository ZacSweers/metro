// MIN_COMPILER_VERSION: 2.5.0-Beta1
// METRO_JVM_ONLY
// GENERATE_CLASSES_IN_IR: false
// OMIT_REDUNDANT_MIRRORS: false

// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
@BindingContainer
abstract class LibraryFields {
  companion {
    // Field providers call the generated helper because Metro can't bypass it with a field access.
    @Provides @JvmField @Named("static") val staticField: String = "static"
  }
}

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt
@DependencyGraph(bindingContainers = [LibraryFields::class])
abstract class Graph {
  @Provides @JvmField @Named("instance") val instanceField: String = "instance"

  @get:Named("static") abstract val static: String
  @get:Named("instance") abstract val instance: String
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("static", graph.static)
  assertEquals("instance", graph.instance)
  return "OK"
}
