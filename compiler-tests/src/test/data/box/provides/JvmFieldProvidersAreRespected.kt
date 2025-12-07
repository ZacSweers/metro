// In this scenario, generated provider factories' newInstance function gets the field directly
@DependencyGraph
abstract class AppGraph {
  @Provides @JvmField val stringField: String = "Hello"
  // Normal property
  @Provides val intAccessor: Int get() = 3

  abstract val string: String
  abstract val int: Int
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.string)
  assertEquals(3, graph.int)
  return "OK"
}