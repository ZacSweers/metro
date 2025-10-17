// GENERATE_ASSISTED_FACTORIES
@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory

  @Provides val int: Int get() = 0
}

@AssistedInject
class ExampleClass(
  val count: Int,
  @Assisted val text: String,
) {
  fun template(): String = text + count

  @AssistedFactory
  interface Factory {
    fun create(@Assisted text: String): ExampleClass
  }
}

fun box(): String {
  val exampleClass = createGraph<AppGraph>().factory.create("hello ")
  assertEquals("hello 0", exampleClass.template())
  return "OK"
}