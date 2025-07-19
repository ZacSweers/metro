// ENABLE_DAGGER_INTEROP
package test

import javax.inject.Inject
import dagger.Lazy

@DependencyGraph
interface ExampleGraph {
  val fooBar: FooBar
}

class Foo @Inject constructor()

class FooBar @Inject constructor(
  val lazy: Lazy<Foo>
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val fooInstance = graph.fooBar.lazy
  assertNotNull(fooInstance)
  assertTrue(fooInstance is dev.zacsweers.metro.interop.dagger.internal.DaggerInteropDoubleCheck)
  assertEquals(fooInstance.get().javaClass.name, "test.Foo")
  return "OK"
}
