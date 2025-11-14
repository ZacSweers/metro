// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

// This test verifies @Binds works correctly across shards
// Tests that alias resolution finds the actual implementation

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
