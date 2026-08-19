// MIN_COMPILER_VERSION: 2.4.20-dev-6138
// GENERATE_CLASSES_IN_IR: true
// MODULE: lib
@AssistedInject
class Example {
  @AssistedFactory
  interface Factory {
    fun create(): Example
  }
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph {
  val factory: Example.Factory
}

fun box(): String {
  val instance = createGraph<AppGraph>().factory.create()
  assertNotNull(instance)
  return "OK"
}
