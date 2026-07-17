// ENABLE_SUSPEND_PROVIDERS

// A suspend graph accessor can construct an @Inject class whose constructor parameters come from
// multiple suspend bindings.

@Inject class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val accountCreator = runBlocking { graph.accountCreator() }
  assertEquals("db", accountCreator.database)
  assertEquals(7, accountCreator.tlsConnection)
  return "OK"
}
