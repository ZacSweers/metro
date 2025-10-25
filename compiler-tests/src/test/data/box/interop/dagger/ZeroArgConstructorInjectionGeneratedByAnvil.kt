// MODULE: lib
// ENABLE_ANVIL_KSP
// DISABLE_METRO

// FILE: ExampleClass.kt
package test

import javax.inject.Inject

class ExampleClass @Inject constructor() {
  // No constructor parameters - anvil-ksp should generate an object Factory
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.exampleClass)
  return "OK"
}
