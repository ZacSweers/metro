// Regression test to ensure we reparent copied lambda parameters
fun interface FunInterface {
  operator fun invoke(param: String)
}

class ExampleClass
@AssistedInject
constructor(@Assisted private val lambdaParam: FunInterface = FunInterface {}) {
  @AssistedFactory
  interface Factory {
    fun create(@Assisted lambdaParam: FunInterface): ExampleClass
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
