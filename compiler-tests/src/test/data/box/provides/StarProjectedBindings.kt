// MODULE: lib
interface Source {
  val item: Lazy<*>
}

@BindingContainer
object Maps {
  @Provides fun values(): Map<String, *> = mapOf("key" to 1)

  @Provides fun keys(): Map<*, Int> = mapOf("key" to 2)

  @Provides fun both(): Map<*, *> = mapOf("key" to 3)
}

// MODULE: main(lib)
@Inject class Consumer(
  val values: Lazy<Map<String, *>>,
  val keys: () -> Map<*, Int>,
)

@DependencyGraph(bindingContainers = [Maps::class])
interface Graph {
  val item: Lazy<*>
  val values: Map<String, *>
  val keys: Map<*, Int>
  val both: Map<*, *>
  val consumer: Consumer

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes source: Source): Graph
  }
}

fun box(): String {
  val source = object : Source {
    override val item = lazy { 4 }
  }
  val graph = createGraphFactory<Graph.Factory>().create(source)
  assertSame(source.item, graph.item)
  assertEquals(1, graph.values["key"])
  assertEquals(2, graph.keys["key"])
  assertEquals(3, graph.both["key"])
  assertEquals(1, graph.consumer.values.value["key"])
  assertEquals(2, graph.consumer.keys()["key"])
  return "OK"
}
