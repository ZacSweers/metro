// ENABLE_DAGGER_INTEROP
// USE_ASSISTED_PARAM_NAMES_AS_IDENTIFIERS: false
// Even when useAssistedParamNamesAsIdentifiers is false, Dagger's @Assisted
// should still use param names for matching (interop behavior).
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.assisted.AssistedFactory

class ExampleClass @AssistedInject constructor(
  @Assisted val count: Int,
  @Assisted val name: String,
) {
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
