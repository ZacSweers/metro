// USE_ASSISTED_PARAM_NAMES_AS_IDENTIFIERS: false
// When disabled, param names are not used as identifiers for Metro's native @Assisted.
// This means params match by type only (legacy behavior)

@AssistedInject
data class ExampleClass(@Assisted val first: Int, @Assisted val second: String) {
  @AssistedFactory
  interface Factory {
    // Parameter names don't need to match when useAssistedParamNamesAsIdentifiers is false
    fun create(a: Int, b: String): ExampleClass
  }
}

@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory
}

fun box(): String {
  val instance = createGraph<AppGraph>().factory.create(1, "hello")
  assertEquals(ExampleClass(1, "hello"), instance)
  return "OK"
}
