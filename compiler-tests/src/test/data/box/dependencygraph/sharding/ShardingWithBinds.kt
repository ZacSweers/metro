// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

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

fun box(): String {
  val graph = createGraph<TestGraph>()
  return when {
    graph.service.repo == null -> "FAIL: repo null"
    graph.repository == null -> "FAIL: repository null"
    graph.service.repo !== graph.repository -> "FAIL: not same instance"
    graph.repository !is RepositoryImpl -> "FAIL: wrong type"
    else -> "OK"
  }
}
