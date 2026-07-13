// ENABLE_SUSPEND_PROVIDERS

var databaseComputations = 0

@Inject
class Database(val url: String, val port: Int) {
  init {
    databaseComputations++
  }
}

@Inject class ReadClient(val database: Database)

@Inject class WriteClient(val database: Database)

@DependencyGraph
interface ExampleGraph {
  suspend fun readClient(): ReadClient

  suspend fun writeClient(): WriteClient

  @Provides suspend fun provideUrl(): String = "db://localhost"

  @Provides fun providePort(): Int = 5432
}

fun box(): String =
  runSuspending {
    databaseComputations = 0
    val graph = createGraph<ExampleGraph>()
    val readDatabase = graph.readClient().database
    val writeDatabase = graph.writeClient().database

    assertEquals("db://localhost", readDatabase.url)
    assertEquals(5432, readDatabase.port)
    assertEquals("db://localhost", writeDatabase.url)
    assertEquals(5432, writeDatabase.port)
    assertNotSame(readDatabase, writeDatabase)
    assertEquals(2, databaseComputations)
    "OK"
  }
