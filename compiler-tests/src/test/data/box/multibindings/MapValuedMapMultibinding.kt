// Each @IntoMap contribution whose value is a Map is one entry in the multibinding.

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, Map<String, Int>>
  val mapProvider: Provider<Map<String, Map<String, Int>>>
  val providerValuesMap: Map<String, Provider<Map<String, Int>>>
}

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @Provides @IntoMap @StringKey("a") fun provideA(): Map<String, Int> = mapOf("a1" to 1, "a2" to 2)

  @Provides @IntoMap @StringKey("b") fun provideB(): Map<String, Int> = mapOf("b1" to 3)
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val expected = mapOf("a" to mapOf("a1" to 1, "a2" to 2), "b" to mapOf("b1" to 3))
  assertEquals(expected, appGraph.map)
  assertEquals(expected, appGraph.mapProvider())
  assertEquals(expected, appGraph.providerValuesMap.mapValues { it.value() })
  return "OK"
}
