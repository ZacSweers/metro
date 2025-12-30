// https://github.com/ZacSweers/metro/issues/1596
// Regression test to ensure we handle Map<K, Provider<V>> in @Provides functions

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, Provider<String>>
  val mapAsString: String
  val holder: Holder
}

@Inject class Holder(val map: Map<String, Provider<String>>)

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @Provides @IntoMap @StringKey("key1") fun provideFirst(): String = "first"

  @Provides @IntoMap @StringKey("key2") fun provideSecond(): String = "second"

  @Provides
  fun provideMapAsString(map: Map<String, Provider<String>>): String =
    map.values.joinToString { it() }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val expected = mapOf("key1" to "first", "key2" to "second")
  assertEquals(expected, appGraph.map.mapValues { it.value() })
  assertEquals(expected, appGraph.holder.map.mapValues { it.value() })
  assertEquals("first, second", appGraph.mapAsString)
  return "OK"
}
