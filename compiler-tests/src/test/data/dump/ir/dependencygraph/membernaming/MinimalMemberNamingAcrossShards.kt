// MEMBER_NAMING_STRATEGY: TYPED
// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * Verifies that nested-shard graphs collapse each shard's binding properties to the MINIMAL
 * vocabulary even when the strategy is TYPED, and that each shard's NameAllocator restarts
 * independently. The same name string (e.g. `provider`, `provider2`) recurs across shard
 * classes -- this is the cross-class naming behavior that lets the DEX string table dedup.
 *
 * Graph-class supplemental properties stay under TYPED (the base namer from context).
 *
 * Each MemberNamer.Kind is exercised:
 *  - PROVIDER: regular @Inject chain (Config, Repository, Service) -- collapses to provider*
 *              in each shard.
 *  - INSTANCE: @Provides bound-instance creator parameter (ProvidedExample) -- supplemental
 *              property on the graph class, named under TYPED.
 *  - FACTORY:  an @AssistedInject target shared by two @AssistedFactory accessors. The
 *              shared target's MetroFactory becomes a binding field that lands in a shard;
 *              the FACTORY kind is collapsed to provider* by the per-shard MINIMAL override.
 */

class ProvidedExample

@SingleIn(AppScope::class) @Inject class Config(val example: ProvidedExample)

@SingleIn(AppScope::class) @Inject class Repository(val config: Config)

@SingleIn(AppScope::class) @Inject class Service(val repository: Repository)

@AssistedInject class SharedWorker(val service: Service, @Assisted val name: String)

@AssistedFactory
fun interface PrimaryWorkerFactory {
  fun create(name: String): SharedWorker
}

@AssistedFactory
fun interface SecondaryWorkerFactory {
  fun create(name: String): SharedWorker
}

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service: Service
  val primaryWorkerFactory: PrimaryWorkerFactory
  val secondaryWorkerFactory: SecondaryWorkerFactory

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides example: ProvidedExample): TestGraph
  }
}
