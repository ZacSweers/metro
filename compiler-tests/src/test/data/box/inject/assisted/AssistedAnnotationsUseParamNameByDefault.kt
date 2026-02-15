@AssistedInject
data class ExampleClass(
  @Assisted val size: Int,
  @Assisted val invalidCount: Int,
) {
  @AssistedFactory
  interface Factory {
    fun create(size: Int, invalidCount: Int): ExampleClass
  }
}

@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory
}

fun box(): String {
  val instance = createGraph<AppGraph>().factory.create(1, 2)
  assertEquals(ExampleClass(1, 2), instance)
  return "OK"
}