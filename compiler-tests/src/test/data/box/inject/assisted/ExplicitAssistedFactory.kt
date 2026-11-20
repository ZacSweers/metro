@AssistedInject
class ExampleClass(@Assisted val count: Int, private val message: String) {
  fun call(): String = message + count
}

@AssistedFactory
fun interface ExampleClassFactory {
  fun create(count: Int): ExampleClass
}

@DependencyGraph
interface ExampleGraph {
  val factory: ExampleClassFactory

  @Provides val message: String get() = "Hello, "
}

fun box(): String {
  val factory = createGraph<ExampleGraph>().factory
  assertEquals("Hello, 2", factory.create(2).call())
  return "OK"
}
