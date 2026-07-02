// One `@SuspendAware` class depending on another. The outer class's factory ctor takes the
// inner class's `SuspendProvider<…>` directly (no wrap needed).

@SuspendAware
@Inject
class Database(val region: String)

@SuspendAware
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
