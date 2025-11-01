// Regression test for Dagger interop map multibinding with @Provides
// Tests that Map<K, Provider<V>> correctly uses dagger.internal.Provider
// and doesn't double-wrap providers when using @IntoMap with @Provides
// ENABLE_DAGGER_INTEROP
import javax.inject.Provider
import dagger.Module

class AppInfo(val appName: String, val version: String)

@Module
@ContributesTo(AppScope::class)
class AppInfoUnpackers {
  @Provides
  @IntoMap
  @StringKey("name")
  fun provideName(): String = "TestApp"

  @Provides
  @IntoMap
  @StringKey("version")
  fun provideVersion(): String = "1.0.0"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val data: Provider<Map<String, Provider<String>>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(
    mapOf(
      "name" to "TestApp",
      "version" to "1.0.0"
    ),
    graph.data.get().mapValues { it.value.get() }
  )
  return "OK"
}
