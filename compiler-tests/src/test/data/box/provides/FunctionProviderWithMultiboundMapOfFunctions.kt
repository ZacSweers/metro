// ENABLE_FUNCTION_PROVIDERS
@DependencyGraph
abstract class ExampleGraph {
  var counter = 0

  abstract val map: Map<String, () -> Int>
  abstract val consumer: MapFunctionConsumer

  @Provides @IntoMap @StringKey("counter") fun provideCounter(): Int = counter++

  @Provides @IntoMap @StringKey("fixed") fun provideFixed(): Int = 42
}

@Inject class MapFunctionConsumer(val map: Map<String, () -> Int>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Function providers in map should act as providers
  assertEquals(0, graph.map["counter"]!!())
  assertEquals(1, graph.map["counter"]!!())
  assertEquals(42, graph.map["fixed"]!!())
  // Injected consumer should behave the same
  val consumer = graph.consumer
  assertEquals(2, consumer.map["counter"]!!())
  assertEquals(42, consumer.map["fixed"]!!())
  return "OK"
}
