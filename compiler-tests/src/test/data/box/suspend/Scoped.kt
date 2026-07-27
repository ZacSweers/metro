// ENABLE_SUSPEND_PROVIDERS

var databaseComputations = 0

@Inject
@SingleIn(AppScope::class)
class AccountCreator(val database: String)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  suspend fun otherCreator(): AccountCreator

  @Provides
  suspend fun provideDatabase(): String {
    databaseComputations++
    return "db"
  }
}

fun box(): String =
  runBlocking {
    databaseComputations = 0
    val graph = createGraph<ExampleGraph>()
    val accountCreator = graph.accountCreator()
    val otherCreator = graph.otherCreator()

    assertEquals("db", accountCreator.database)
    assertSame(accountCreator, otherCreator)
    assertEquals(1, databaseComputations)
    "OK"
  }
