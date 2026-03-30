// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun <T : Any> App(value: T): T {
  return value
}

@DependencyGraph
interface ExampleGraph {
  val app: App<String>

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides message: String): ExampleGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create("Hello!")
  assertEquals("Hello!", graph.app.invoke())
  return "OK"
}
