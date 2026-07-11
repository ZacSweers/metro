// ENABLE_SUSPEND_PROVIDERS

// Suspend-ness propagates through constructor-injected chains: Database depends on a suspend
// @Provides, AccountCreator depends on Database. Both are transitively suspend and must be
// accessed from a suspend context.

@Inject
class Database(val region: String)

@Inject
class AccountCreator(val database: Database)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideRegion(): String = "us-east-1"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
