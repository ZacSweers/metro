// ENABLE_SUSPEND_PROVIDERS

// Deep and diamond-shaped suspend propagation through multiple non-suspend hops:
//
//   suspend Config
//     ├── Database(config: Config)            — hop 1: transitively suspend
//     │     └── Repository(db: Database)      — hop 2: transitively suspend
//     │           └── suspend accessor
//     ├── Metrics(config: suspend () -> Config) — deferred: NOT suspend, plain accessor
//     └── Handler(db: suspend () -> Database)   — deferral of a TRANSITIVELY suspend binding
//           └── App(handler: Handler)            — chain broken; stays non-suspend through a
//                                                  further hop, plain accessor
//
// Also pins scoping in the middle of a deep chain: Database is scoped, so the two paths that
// reach it (Repository eagerly, Handler lazily) share one computation.

var configComputations = 0
var dbComputations = 0

class Config(val url: String)

@Inject
@SingleIn(AppScope::class)
class Database(val config: Config) {
  init {
    dbComputations++
  }
}

@Inject class Repository(val database: Database)

@Inject class Metrics(val config: suspend () -> Config)

@Inject class Handler(val database: suspend () -> Database)

@Inject class App(val handler: Handler)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun repository(): Repository

  // Deferred consumers stay non-suspend even though their deps are (transitively) suspend
  val metrics: Metrics
  val app: App

  @Provides
  suspend fun provideConfig(): Config {
    configComputations++
    return Config("jdbc:test")
  }
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Non-suspend accessors resolve without any suspension having happened
  val metrics = graph.metrics
  val app = graph.app
  assertEquals(0, configComputations)
  assertEquals(0, dbComputations)

  return runBlocking {
    // Two-hop transitive chain resolves through nested suspend factories
    val repository = graph.repository()
    assertEquals("jdbc:test", repository.database.config.url)
    assertEquals(1, dbComputations)

    // Handler's deferred path reaches the SAME scoped Database instance, no recomputation
    val dbViaHandler = app.handler.database()
    assertEquals(1, dbComputations)
    assertTrue(dbViaHandler === repository.database)

    // Metrics' deferred Config is unscoped: fresh computation per invocation
    val configCount = configComputations
    metrics.config()
    assertEquals(configCount + 1, configComputations)

    "OK"
  }
}
