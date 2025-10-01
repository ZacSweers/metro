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
  fun template(): String = message + count
}

@AssistedFactory
interface ExampleClassFactory {
  fun foo(): ExampleClass = create(0)
  fun bar() {}

  fun create(count: Int): ExampleClass
}

fun box(): String {
  val exampleClass = createGraph<AppGraph>().factory.create(0)
  assertEquals("Hello, 0", exampleClass.template())
  return "OK"
}