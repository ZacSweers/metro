// IGNORE_BACKEND: JS_IR

// In this scenario, generated provider factories' newInstance function gets the field directly
@DependencyGraph
abstract class AppGraph {
  @Provides @JvmField val stringField: String = "Hello"

  abstract val string: String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.string)
  return "OK"
}
