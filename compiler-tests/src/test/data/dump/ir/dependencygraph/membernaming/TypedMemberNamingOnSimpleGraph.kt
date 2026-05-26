// MEMBER_NAMING_STRATEGY: TYPED

/*
 * Verifies the TYPED naming strategy in single-shard (graph-as-shard) mode. The graph class
 * should expose provider/instance/factory fields under a small typed vocabulary
 * (provider, provider2, ...; instance, instance2, ...; factory, factory2, ...) instead of
 * names derived from the bindings.
 *
 * Each MemberNamer.Kind is exercised:
 *  - PROVIDER: regular @Inject chain (Config, Repository, Service).
 *  - INSTANCE: @Provides bound-instance creator parameter (ProvidedExample) -- supplemental
 *              property.
 *  - FACTORY:  an @AssistedInject target shared by two @AssistedFactory accessors. The shared
 *              target's generated MetroFactory is stored as a graph field, hitting the
 *              `binding.isAssisted` branch in IrGraphGenerator.computeBindingMetadata.
 */

class ProvidedExample

@Inject class Config(val example: ProvidedExample)

@Inject class Repository(val config: Config)

@Inject class Service(val repository: Repository)

@AssistedInject class SharedWorker(val service: Service, @Assisted val name: String)

@AssistedFactory
fun interface PrimaryWorkerFactory {
  fun create(name: String): SharedWorker
}

@AssistedFactory
fun interface SecondaryWorkerFactory {
  fun create(name: String): SharedWorker
}

@DependencyGraph
interface TestGraph {
  val service: Service
  val primaryWorkerFactory: PrimaryWorkerFactory
  val secondaryWorkerFactory: SecondaryWorkerFactory

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides example: ProvidedExample): TestGraph
  }
}
