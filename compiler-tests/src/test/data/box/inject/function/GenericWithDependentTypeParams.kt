// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
fun <T : Any, R : T> App(value: T, value2: R): T {
  return value
}

@DependencyGraph
interface ExampleGraph {
  val app: App<Number, Int>

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides number: Number, @Provides int: Int): ExampleGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<ExampleGraph.Factory>().create(42, 7)
  assertEquals(42, graph.app.invoke())
  return "OK"
}
