// GENERATE_ASSISTED_FACTORIES
@AssistedInject
class ExampleClass(
  @Assisted val count: Int = 2,
  val message: String,
)

@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory
  @Provides val string: String get() = "hello"
}

fun box(): String {
  val factory = createGraph<AppGraph>().factory
  // Smoke test to ensure that the FIR-generated create() supports default args
  val created = factory.create()
  // Default value
  assertEquals(2, created.count)
  return "OK"
}