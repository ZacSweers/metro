// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 2
// MULTIBINDING_GETTER_THRESHOLD: 1

var constructions = 0

class Item(val id: Int) {
  init {
    constructions++
  }
}

@Inject
class Consumer(val set: Provider<Set<Item>>, val map: Provider<Map<String, Item>>)

@DependencyGraph
interface AppGraph {
  val consumer: Consumer
  val set: Provider<Set<Item>>
  val otherSet: Provider<Set<Item>>
  val map: Provider<Map<String, Item>>
  val otherMap: Provider<Map<String, Item>>
  val providerMap: Provider<Map<String, Provider<Item>>>
  val otherProviderMap: Provider<Map<String, Provider<Item>>>

  @Provides @IntoSet fun firstSetItem(): Item = Item(1)
  @Provides @IntoSet fun secondSetItem(): Item = Item(2)
  @Provides @IntoMap @StringKey("first") fun firstMapItem(): Item = Item(3)
  @Provides @IntoMap @StringKey("second") fun secondMapItem(): Item = Item(4)
}

fun box(): String {
  constructions = 0
  val graph = createGraph<AppGraph>()
  assertSame(graph.set, graph.set)
  assertSame(graph.set, graph.otherSet)
  assertSame(graph.set, graph.consumer.set)
  assertSame(graph.map, graph.otherMap)
  assertSame(graph.map, graph.consumer.map)
  assertSame(graph.providerMap, graph.otherProviderMap)
  assertEquals(0, constructions)

  val firstSet = graph.set()
  val secondSet = graph.set()
  assertEquals(setOf(1, 2), firstSet.map { it.id }.toSet())
  assertNotSame(firstSet, secondSet)
  assertNotSame(firstSet.first(), secondSet.first())

  val firstMap = graph.map()
  val secondMap = graph.map()
  assertEquals(mapOf("first" to 3, "second" to 4), firstMap.mapValues { it.value.id })
  assertNotSame(firstMap, secondMap)
  assertNotSame(firstMap.getValue("first"), secondMap.getValue("first"))
  assertEquals(8, constructions)

  val providers = graph.providerMap()
  val otherProviders = graph.providerMap()
  assertNotSame(providers, otherProviders)
  for (key in listOf("first", "second")) {
    assertSame(providers.getValue(key), otherProviders.getValue(key))
  }
  assertEquals(8, constructions)
  assertEquals(3, providers.getValue("first")().id)
  assertNotSame(providers.getValue("first")(), providers.getValue("first")())
  assertNotSame(graph.set, createGraph<AppGraph>().set)
  return "OK"
}
