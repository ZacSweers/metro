// ENABLE_DAGGER_INTEROP

// Anvil may generate objects

// MODULE: lib
// FILE: ExampleClass.kt
package test

import javax.inject.Inject

class ExampleClass @Inject constructor()

// FILE: ExampleClass_Factory.kt
package test

import dagger.internal.Factory

object ExampleClass_Factory : Factory<ExampleClass> {
  override fun get(): ExampleClass = newInstance()

  @JvmStatic
  fun create(): ExampleClass_Factory = ExampleClass_Factory

  @JvmStatic
  fun newInstance(): ExampleClass = ExampleClass()
}

// MODULE: main(lib)
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
