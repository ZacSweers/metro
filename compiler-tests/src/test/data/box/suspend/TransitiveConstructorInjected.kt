// ENABLE_SUSPEND_PROVIDERS

@Inject
class Database(val region: String)

@Inject
class AccountCreator(val database: Database)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideRegion(): String = "us-east-1"
}

fun box(): String =
  runBlocking {
    val graph = createGraph<ExampleGraph>()
    assertEquals("us-east-1", graph.accountCreator().database.region)
    "OK"
  }
