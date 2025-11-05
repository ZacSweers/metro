// ENABLE_GUICE_INTEROP
import jakarta.inject.Inject

@DependencyGraph
interface ExampleGraph {
  val fooBar: FooBar
}

class Foo @Inject constructor()

class FooBar @Inject constructor(val lazy: Lazy<Foo>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val fooInstance = graph.fooBar.lazy
  assertNotNull(fooInstance)
  // Verify it's a Kotlin Lazy
  assertTrue(fooInstance is Lazy<*>)
  assertEquals(fooInstance.value.javaClass.name, "Foo")
  return "OK"
}
