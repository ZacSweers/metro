// ENABLE_SUSPEND_PROVIDERS
// FORCE_PROVIDER_GETTERS: true
// ENABLE_GRAPH_SHARDING: false
// MULTIBINDING_GETTER_THRESHOLD: 1

var computations = 0

class Value

@Inject class Consumer(val value: Value)
@SingleIn(AppScope::class)
@Inject class ScopedConsumer(val value: Value)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: SuspendProvider<Consumer>
  val scoped: SuspendProvider<ScopedConsumer>
  val providerMap: Provider<Map<String, SuspendProvider<Value>>>

  @Provides suspend fun value(): Value {
    computations++
    return Value()
  }

  @Provides @IntoMap @StringKey("first") suspend fun firstMap(value: Value): Value = value
  @Provides @IntoMap @StringKey("second") suspend fun secondMap(value: Value): Value = value
}

fun box(): String = runBlocking {
  computations = 0
  val graph = createGraph<AppGraph>()
  assertNotSame(graph.consumer, graph.consumer)
  assertNotSame(graph.providerMap, graph.providerMap)
  val mapProvider = graph.providerMap
  val map = mapProvider()
  val otherMap = mapProvider()
  assertNotSame(map, otherMap)
  assertSame(map.getValue("first"), otherMap.getValue("first"))
  assertEquals(0, computations)
  val first = graph.consumer()
  val second = graph.consumer()
  assertNotSame(first.value, second.value)
  assertEquals(2, computations)
  assertNotSame(map.getValue("first")(), map.getValue("first")())
  assertEquals(4, computations)
  assertSame(graph.scoped(), graph.scoped())
  assertEquals(5, computations)
  "OK"
}
