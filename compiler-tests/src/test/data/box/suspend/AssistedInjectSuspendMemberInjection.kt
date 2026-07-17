// ENABLE_SUSPEND_PROVIDERS

// An @AssistedInject target with a suspend constructor dep AND a non-suspend @Inject member. The
// generated suspend assisted impl awaits the suspend ctor dep and must still run member injection
// for the non-suspend member after construction.

class Database(val value: String)

class Config(val name: String)

@AssistedInject
class AccountCreator(@Assisted val region: String, val database: Database) {
  @Inject lateinit var config: Config

  @AssistedFactory
  interface Factory {
    suspend fun create(region: String): AccountCreator
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: AccountCreator.Factory

  @Provides suspend fun provideDatabase(): Database = Database("db")

  @Provides fun provideConfig(): Config = Config("cfg")
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val accountCreator = runBlocking { graph.factory.create("us-east-1") }
  assertEquals("us-east-1", accountCreator.region)
  assertEquals("db", accountCreator.database.value)
  assertEquals("cfg", accountCreator.config.name)
  return "OK"
}
