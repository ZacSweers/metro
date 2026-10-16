// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true
// FILE: Library.kt
@DependencyGraph
interface GenericGraph<T : CharSequence>
// MODULE: main(lib)
// FILE: Main.kt
fun box(): String {
  assertNotNull(GenericGraph<String>())
  assertNotNull(createGraph<GenericGraph<String>>())
  return "OK"
}
