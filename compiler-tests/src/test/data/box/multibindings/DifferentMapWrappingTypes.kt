// TODO
//  providing a Map<String, Int> should not make it
//  possible to get a Map<String, Provider<Int>> later
@DependencyGraph
interface ExampleGraph {
  @Provides @IntoMap @StringKey("a") fun provideEntryA(): Int = 1

  @Provides @IntoMap @StringKey("b") fun provideEntryB(): Int = 2

  @Provides @IntoMap @StringKey("c") fun provideEntryC(): Int = 3

  // Inject it with different formats
  val directMap: Map<String, Int>
  val providerValueMap: Map<String, Provider<Int>>
  val providerMap: Provider<Map<String, Int>>
  val providerOfProviderValueMap: Provider<Map<String, Provider<Int>>>
  val lazyOfProviderValueMap: Lazy<Map<String, Provider<Int>>>
  val providerOfLazyOfProviderValueMap: Provider<Lazy<Map<String, Provider<Int>>>>

  // Class that injects the map with yet another format
  val exampleClass: ExampleClass
}

@Inject class ExampleClass(val map: Map<String, Provider<Int>>)

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Test direct map
  val directMap = graph.directMap
  assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), directMap)

  // Test map with provider values
  val providerValueMap = graph.providerValueMap
  assertEquals(
    mapOf("a" to 1, "b" to 2, "c" to 3),
    providerValueMap.mapValues { (_, value) -> value() },
  )

  // Test provider of map
  val providerMap = graph.providerMap
  assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), providerMap())

  // Test provider of map with provider values
  val providerOfProviderValueMap = graph.providerOfProviderValueMap
  assertEquals(
    mapOf("a" to 1, "b" to 2, "c" to 3),
    providerOfProviderValueMap().mapValues { (_, value) -> value() },
  )

  // Test lazy of map with provider values
  val lazyOfProviderValueMap = graph.lazyOfProviderValueMap
  assertEquals(
    mapOf("a" to 1, "b" to 2, "c" to 3),
    lazyOfProviderValueMap.value.mapValues { (_, value) -> value() },
  )

  // Test provider of lazy map with provider values
  val providerOfLazyOfProviderValueMap = graph.providerOfLazyOfProviderValueMap
  assertEquals(
    mapOf("a" to 1, "b" to 2, "c" to 3),
    providerOfLazyOfProviderValueMap().value.mapValues { (_, value) -> value() },
  )

  // Test injected class
  val exampleClass = graph.exampleClass
  val injectedMap = exampleClass.map
  assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), injectedMap.mapValues { (_, value) -> value() })
  return "OK"
}
