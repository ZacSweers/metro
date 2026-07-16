// ENABLE_SUSPEND_PROVIDERS

// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runtime-coroutines is not yet wired into JS box tests

// A suspend accessor on an @Includes-ed graph is a suspend binding in the consuming graph:
// consumers must be suspend (or defer), and codegen must wrap the included graph's suspend getter
// in a suspend provider rather than a plain provider lambda.

var dbComputations = 0

abstract class AppScope private constructor()

@DependencyGraph(scope = AppScope::class)
interface DatabaseGraph {
  suspend fun database(): String

  val port: Int

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDatabase(): String {
    dbComputations++
    return "db"
  }

  @Provides fun providePort(): Int = 5432
}

@Inject class Repository(val database: String, val port: Int)

@DependencyGraph
interface AppGraph {
  suspend fun repository(): Repository

  // Deferred consumption stays non-suspend
  val databaseProvider: suspend () -> String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes databaseGraph: DatabaseGraph): AppGraph
  }
}

fun box(): String {
  val dbGraph = createGraph<DatabaseGraph>()
  val appGraph = createGraphFactory<AppGraph.Factory>().create(dbGraph)
  return kotlinx.coroutines.runBlocking {
    val repository = appGraph.repository()
    assertEquals("db", repository.database)
    assertEquals(5432, repository.port)
    assertEquals("db", appGraph.databaseProvider())
    // Scoped in the included graph — computed once even across graphs
    assertEquals(1, dbComputations)
    "OK"
  }
}
