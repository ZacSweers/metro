// ENABLE_DAGGER_KSP

// Regression test for @IntoSet/@IntoMap/@MapKey contributions being dropped when a Dagger module
// is read from external class metadata (compiled by Dagger's processor only, not Metro).

// MODULE: lib
// DISABLE_METRO
// FILE: ExternalModules.kt
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import javax.inject.Inject

interface PreloadService
class BoundService @Inject constructor() : PreloadService
class ProvidedService : PreloadService
class MapValue(val name: String)

@Module
abstract class BindsModule {
  @Binds @IntoSet abstract fun bindIntoSet(service: BoundService): PreloadService
}

@Module
class ProvidesModule {
  @Provides @IntoSet fun provideIntoSet(): PreloadService = ProvidedService()
  @Provides @IntoMap @StringKey("alpha") fun provideAlpha(): MapValue = MapValue("alpha")
  @Provides @IntoMap @StringKey("beta") fun provideBeta(): MapValue = MapValue("beta")
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
// FILE: AppGraph.kt

@DependencyGraph(bindingContainers = [BindsModule::class])
interface AppGraph {
  val preloadServices: Set<PreloadService>
  val mapValues: Map<String, MapValue>

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes providesModule: ProvidesModule): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(ProvidesModule())
  assertEquals(2, graph.preloadServices.size)
  assertEquals(setOf("alpha", "beta"), graph.mapValues.keys)
  assertEquals("alpha", graph.mapValues["alpha"]!!.name)
  assertEquals("beta", graph.mapValues["beta"]!!.name)
  return "OK"
}
