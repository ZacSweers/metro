// ENABLE_SUSPEND_PROVIDERS

// A constructor-injected class with several suspend deps, resolved sequentially.

val dependencyEvaluationOrder = mutableListOf<String>()

@Inject
class AccountCreator(val database: String, val tlsConnection: Int, val region: Long)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides
  suspend fun provideDatabase(): String {
    dependencyEvaluationOrder += "database"
    return "db"
  }

  @Provides
  suspend fun provideTls(): Int {
    dependencyEvaluationOrder += "tls"
    return 7
  }

  @Provides
  suspend fun provideRegion(): Long {
    dependencyEvaluationOrder += "region"
    return 42L
  }
}

fun box(): String {
  dependencyEvaluationOrder.clear()
  return runSuspending {
    val accountCreator = createGraph<ExampleGraph>().accountCreator()
    assertEquals("db", accountCreator.database)
    assertEquals(7, accountCreator.tlsConnection)
    assertEquals(42L, accountCreator.region)
    assertEquals(listOf("database", "tls", "region"), dependencyEvaluationOrder)
    "OK"
  }
}
