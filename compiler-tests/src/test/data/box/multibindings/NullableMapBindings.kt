@Inject class Consumer(val values: Map<String, String>?)

@DependencyGraph
interface Graph {
  val consumer: Consumer
  val values: Map<String, String>
  val nullable: Map<String, String>?
  val deferred: Lazy<Map<String, String>?>

  @Provides fun supply(): Map<String, String> = mapOf("key" to "value")
  @Provides fun supplyNullable(): Map<String, String>? = null
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals("value", graph.values["key"])
  assertNull(graph.nullable)
  assertNull(graph.consumer.values)
  assertNull(graph.deferred.value)
  return "OK"
}
