// Regression test for https://github.com/ZacSweers/metro/issues/1235

@AssistedInject
class Example(val dep: Dependency) {
  @AssistedFactory
  interface Factory {
    fun create(): Example
  }
}

@Inject class Dependency

@DependencyGraph
interface AppGraph {
  val factory: Example.Factory
}

fun box(): String {
  val instance = assertNotNull(createGraph<AppGraph>().factory.create())
  assertNotNull(instance.dep)
  return "OK"
}
