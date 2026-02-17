// Basically just a smoke test to ensure that even though we
// dedupe factory inputs, we still call provider invokes appropriately
@Inject
class Example(val a: Int, val b: Int) {
  fun sum() = a + b
}

@DependencyGraph
abstract class AppGraph {
  private var count = 0
  abstract val example: Example

  @Provides fun provideInt(): Int = count++
}

fun box(): String {
  assertEquals(1, createGraph<AppGraph>().example.sum())
  return "OK"
}
