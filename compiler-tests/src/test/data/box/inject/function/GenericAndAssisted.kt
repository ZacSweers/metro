// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun <T : Any> App(value: T, @Assisted extra: String): String {
  return "$value $extra"
}

@DependencyGraph
interface ExampleGraph {
  val app: App<Int>

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides int: Int): ExampleGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(42)
  assertEquals("42 world", graph.app.invoke("world"))
  return "OK"
}
