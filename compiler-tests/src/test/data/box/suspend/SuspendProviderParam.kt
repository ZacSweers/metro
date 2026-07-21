// ENABLE_SUSPEND_PROVIDERS

var databaseComputations = 0

@Inject
class AccountCreator(val database: SuspendProvider<String>)

@DependencyGraph
interface ExampleGraph {
  val accountCreator: AccountCreator

  @Provides
  suspend fun provideDatabase(): String {
    databaseComputations++
    return "db"
  }
}

fun box(): String {
  databaseComputations = 0
  val graph = createGraph<ExampleGraph>()
  val accountCreator = graph.accountCreator
  assertEquals(0, databaseComputations)
  return runBlocking {
    assertEquals("db", accountCreator.database())
    assertEquals(1, databaseComputations)
    "OK"
  }
}
