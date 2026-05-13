// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun <T : Any> App(value: T, lazyValue: Lazy<T>): List<T> {
  return listOf(value, lazyValue.value)
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
  assertEquals(listOf("Hello!", "Hello!"), graph.app.invoke())
  return "OK"
}
