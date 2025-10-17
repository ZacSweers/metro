// GENERATE_ASSISTED_FACTORIES
@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory

  @Provides val string: String get() = "Hello, "
}

@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
  val message: String,
) {
  fun call(): String = message + count
}

@AssistedFactory
abstract class ExampleClassFactory {
  protected abstract fun create(count: Int): ExampleClass
}

fun box(): String {
  val exampleClass = createGraph<AppGraph>().factory.create(2)
  assertEquals("Hello, 2", exampleClass.call())
  return "OK"
}