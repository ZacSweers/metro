// Regression test to ensure we reparent copied lambda parameters
class ExampleClass @AssistedInject constructor(@Assisted private val lambdaParam: (String) -> Unit = {}) {
  @AssistedFactory
  interface Factory {
    fun create(@Assisted lambdaParam: (String) -> Unit): ExampleClass
  }
}

@DependencyGraph
interface AppGraph {
  val exampleClassFactory: ExampleClass.Factory
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factory = graph.exampleClassFactory
  val instance = factory.create {}
  assertTrue(instance is ExampleClass)
  return "OK"
}
