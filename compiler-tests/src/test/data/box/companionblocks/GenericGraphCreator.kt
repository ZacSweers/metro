// MIN_COMPILER_VERSION: 2.5.0-Beta1
// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
@DependencyGraph
interface GenericGraph<T : CharSequence>
// MODULE: main(lib)
// FILE: Main.kt
fun acceptsGraph(graph: GenericGraph<String>) = graph
fun box(): String {
  acceptsGraph(GenericGraph<String>())
  acceptsGraph(createGraph<GenericGraph<String>>())
  return "OK"
}
