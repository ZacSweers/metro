// MODULE: lib
// ENABLE_ANVIL_KSP
// DISABLE_METRO
// FILE: ExampleClass.kt
package test

import javax.inject.Inject
import javax.inject.Provider
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory

class ExampleClass @AssistedInject constructor(@Assisted intValue: Int) {
  @AssistedFactory
  interface Factory {
    fun create(intValue: Int): ExampleClass
  }
}

class NoArgClass @AssistedInject constructor() {
  @AssistedFactory
  interface Factory {
    fun create(): NoArgClass
  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface ExampleGraph {
  val exampleClassFactory: ExampleClass.Factory
  val noArgFactory: NoArgClass.Factory
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val factory = graph.exampleClassFactory
  assertNotNull(factory.create(1))
  assertNotNull(graph.noArgFactory.create())
  return "OK"
}
