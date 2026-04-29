// WITH_DAGGER
// GENERATE_ASSISTED_FACTORIES

// Tests that assisted factory gen handles identifiers in interop

@Suppress("DEPRECATION")
@AssistedInject
class ExampleClass(
  @dagger.assisted.Assisted("1") val count1: Int,
  @dagger.assisted.Assisted("2") val count2: Int,
  val message: String,
) {
  fun call(): String = message + count1 + " " + count2
}

@DependencyGraph
interface AppGraph {
  val factory: ExampleClass.Factory
  @Provides val string: String get() = "Hello, "
}

fun box(): String {
  val factory = createGraph<AppGraph>().factory
  // Smoke test to ensure that the FIR-generated create() respects identifiers. Note the order switch
  val created = factory.create(count2 = 3, count1 = 2)
  // Default value
  assertEquals("Hello, 2 3", created.call())
  return "OK"
}