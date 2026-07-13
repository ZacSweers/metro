// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

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

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
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
