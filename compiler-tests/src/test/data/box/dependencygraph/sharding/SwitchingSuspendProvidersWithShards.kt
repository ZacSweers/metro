// ENABLE_SUSPEND_PROVIDERS
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1
// STATEMENTS_PER_INIT_FUN: 1

class Connection(val endpoint: String)

@SingleIn(AppScope::class)
@Inject
class Repository(val connection: Connection)

@SingleIn(AppScope::class)
@Inject
class DeferredRepositoryConsumer(val repository: SuspendProvider<Repository>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val deferredConsumer: DeferredRepositoryConsumer

  suspend fun repository(): Repository

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideConnection(): Connection = Connection("db://localhost")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val consumer = graph.deferredConsumer

  return runBlocking {
    val deferredRepository = consumer.repository()
    val directRepository = graph.repository()

    assertEquals("db://localhost", deferredRepository.connection.endpoint)
    assertSame(deferredRepository, directRepository)
    "OK"
  }
}
