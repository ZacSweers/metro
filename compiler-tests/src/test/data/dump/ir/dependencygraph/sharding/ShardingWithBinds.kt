// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that @Binds bindings work correctly with sharding.
 *
 * Graph structure: @Binds binding from RepositoryImpl to Repository interface, used by Service
 * Expected shards: @Binds bindings distributed across shards
 *
 * Validation: Generated IR shows @Binds type aliasing and alias resolution working across shards
 */

interface Repository

@SingleIn(AppScope::class) @Inject class RepositoryImpl : Repository

@SingleIn(AppScope::class) @Inject class Service(val repo: Repository)

@BindingContainer
@ContributesTo(AppScope::class)
interface AppModule {
  @Binds
  fun bindRepository(impl: RepositoryImpl): Repository
}

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val repository: Repository
  val service: Service
}
