// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun App(message: String): String {
  return message
}

private fun App(message: String, loading: Boolean = false): String {
  return if (loading) {
    "loading: $message"
  } else {
    message
  }
}

@DependencyGraph
interface ExampleGraph {
  val app: App

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides message: String): ExampleGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create("Hello, world!")
  assertEquals("Hello, world!", graph.app.invoke())
  return "OK"
}
