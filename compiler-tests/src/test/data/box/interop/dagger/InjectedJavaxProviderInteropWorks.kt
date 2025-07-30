// ENABLE_DAGGER_INTEROP
package test

import javax.inject.Inject
import javax.inject.Provider

@DependencyGraph
interface ExampleGraph {
  val fooBar: FooBar
}

class Foo @Inject constructor()

class FooBar @Inject constructor(
  val provider: Provider<Foo>
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val fooInstance = graph.fooBar.provider
  assertNotNull(fooInstance)
  assertEquals(fooInstance.get().javaClass.name, "test.Foo")
  return "OK"
}
