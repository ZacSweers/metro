// Test for Dagger interop map multibinding with Lazy values
// Tests that Map<K, Lazy<V>> and Map<K, Provider<Lazy<V>>> work correctly with Dagger interop
// ENABLE_DAGGER_INTEROP
import dagger.Lazy
import dagger.Module
import javax.inject.Provider

@Module
@ContributesTo(AppScope::class)
class LazyMapModule {
  @Provides @IntoMap @StringKey("a") fun provideA(): Int = 1

  @Provides @IntoMap @StringKey("b") fun provideB(): Int = 2

  @Provides @IntoMap @StringKey("c") fun provideC(): Int = 3
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  // Map with Lazy values
  val lazyMap: Map<String, Lazy<Int>>

  // Map with Provider<Lazy> values
  val providerLazyMap: Map<String, Provider<Lazy<Int>>>

  // Provider wrapping a Map with Lazy values
  val providerOfLazyMap: Provider<Map<String, Lazy<Int>>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()

  // Test Map<String, Lazy<Int>>
  val lazyMap = graph.lazyMap
  assertEquals(mapOf("a" to 1, "b" to 2, "c" to 3), lazyMap.mapValues { it.value.get() })

  // Test Map<String, Provider<Lazy<Int>>>
  val providerLazyMap = graph.providerLazyMap
  assertEquals(
    mapOf("a" to 1, "b" to 2, "c" to 3),
    providerLazyMap.mapValues { it.value.get().get() },
  )

  // Test Provider<Map<String, Lazy<Int>>>
  val providerOfLazyMap = graph.providerOfLazyMap
  assertEquals(
    mapOf("a" to 1, "b" to 2, "c" to 3),
    providerOfLazyMap.get().mapValues { it.value.get() },
  )

  return "OK"
}
