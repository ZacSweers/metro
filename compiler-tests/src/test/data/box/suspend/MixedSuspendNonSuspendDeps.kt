// ENABLE_SUSPEND_PROVIDERS

// A class mixing suspend and non-suspend deps. The non-suspend dep is adapted into the
// suspend-flavored slot via SyncSuspendProvider where needed.

@Inject
class AccountCreator(val database: String, val region: Long)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  // suspend
  @Provides suspend fun provideDatabase(): String = "db"

  // non-suspend (this is the CompositeProvider path)
  @Provides fun provideRegion(): Long = 42L
}

fun box(): String =
  runSuspending {
    val accountCreator = createGraph<ExampleGraph>().accountCreator()
    assertEquals("db", accountCreator.database)
    assertEquals(42L, accountCreator.region)
    "OK"
  }
