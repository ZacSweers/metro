// USE_ASSISTED_PARAM_NAMES_AS_IDENTIFIERS: false
@DependencyGraph
interface AssistedInjectGraphWithCustomAssistedKeys {
  val factory: ExampleClass.Factory

  @AssistedInject
  class ExampleClass(@Assisted("1") val intValue1: Int, @Assisted("2") val intValue2: Int) {
    @AssistedFactory
    fun interface Factory {
      fun create(@Assisted("2") intValue2: Int, @Assisted("1") intValue1: Int): ExampleClass
    }
  }
}

fun box(): String {
  val graph = createGraph<AssistedInjectGraphWithCustomAssistedKeys>()
  val factory = graph.factory
  val exampleClass = factory.create(2, 1)
  assertEquals(1, exampleClass.intValue1)
  assertEquals(2, exampleClass.intValue2)
  return "OK"
}