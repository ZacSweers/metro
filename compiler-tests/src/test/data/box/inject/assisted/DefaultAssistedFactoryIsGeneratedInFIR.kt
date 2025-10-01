// GENERATE_ASSISTED_FACTORIES
@AssistedInject
class ExampleClass(
  @Assisted val count: Int,
  val message: String,
)

@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory
  @Provides val string: String get() = "Hello, "
}

fun box(): String {
  val factory = createGraph<AppGraph>().factory
  // Smoke test to ensure that the FIR-generated
  assertEquals(2, factory.create(count = 2).count)
  assertEquals(3, factory.create(count = 3).count)
  return "OK"
}