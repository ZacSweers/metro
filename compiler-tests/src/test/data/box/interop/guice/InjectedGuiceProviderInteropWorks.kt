// ENABLE_GUICE_INTEROP
import com.google.inject.Provider
import jakarta.inject.Inject

@DependencyGraph
interface ExampleGraph {
  val fooBar: FooBar
}

class Foo @Inject constructor()

class FooBar @Inject constructor(val provider: Provider<Foo>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val fooInstance = graph.fooBar.provider
  assertNotNull(fooInstance)
  assertEquals(fooInstance.get().javaClass.name, "Foo")
  return "OK"
}
