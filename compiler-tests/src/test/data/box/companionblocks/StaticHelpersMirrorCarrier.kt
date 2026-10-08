// MIN_COMPILER_VERSION: 2.5.0-Beta1
// LANGUAGE: -AnnotationsInMetadata
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt

@Inject
class MirroredValue private constructor(@Named("value") val value: String)

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt

@DependencyGraph
interface AppGraph {
  val value: MirroredValue

  @Provides
  @Named("value")
  fun value(): String = "mirror"
}

fun box(): String {
  assertEquals("mirror", createGraph<AppGraph>().value.value)
  return "OK"
}
