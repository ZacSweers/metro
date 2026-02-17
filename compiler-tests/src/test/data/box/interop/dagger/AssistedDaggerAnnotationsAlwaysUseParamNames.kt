// ENABLE_DAGGER_INTEROP
// Dagger's @Assisted always uses param names for matching (interop behavior).
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ExampleClass
@AssistedInject
constructor(@Assisted val count: Int, @Assisted val name: String) {
  @AssistedFactory
  interface Factory {
    fun create(count: Int, name: String): ExampleClass
  }
}

@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val instance = graph.factory.create(42, "hello")
  assertEquals(42, instance.count)
  assertEquals("hello", instance.name)
  return "OK"
}
