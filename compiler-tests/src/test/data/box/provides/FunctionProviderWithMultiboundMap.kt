// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
interface ExampleGraph {
  val map: Map<String, Int>
  val mapProvider: () -> Map<String, Int>
  val consumer: MapConsumer

  @Provides @IntoMap @StringKey("one") fun provideOne(): Int = 1

  @Provides @IntoMap @StringKey("two") fun provideTwo(): Int = 2
}

@Inject class MapConsumer(val mapProvider: () -> Map<String, Int>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val expected = mapOf("one" to 1, "two" to 2)
  assertEquals(expected, graph.map)
  assertEquals(expected, graph.mapProvider())
  assertEquals(expected, graph.consumer.mapProvider())
  return "OK"
}
