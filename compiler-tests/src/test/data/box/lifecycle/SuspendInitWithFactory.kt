// ENABLE_SUSPEND_PROVIDERS

// A suspend init callback, or one whose parameters suspend, makes graph creation suspend. The
// graph factory's function has to be suspend.

class Database(val name: String) {
  var isMigrated = false
}

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val database: suspend () -> Database

  @Provides @SingleIn(AppScope::class) suspend fun provideDatabase(): Database = Database("db")

  @OnGraphInit
  suspend fun migrate(database: Database) {
    database.isMigrated = true
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(): AppGraph
  }
}

fun box(): String {
  val factory = createGraphFactory<AppGraph.Factory>()
  val database = runBlocking {
    val graph = factory.create()
    graph.database()
  }
  assertTrue(database.isMigrated)
  return "OK"
}
