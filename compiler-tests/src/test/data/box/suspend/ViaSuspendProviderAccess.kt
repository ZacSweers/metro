// ENABLE_SUSPEND_PROVIDERS

@Inject
class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  val creatorProvider: SuspendProvider<AccountCreator>

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

fun box(): String =
  runSuspending {
    val creator = createGraph<ExampleGraph>().creatorProvider()
    assertEquals("db", creator.database)
    assertEquals(7, creator.tlsConnection)
    "OK"
  }
