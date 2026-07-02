// Scoped `@SuspendAware` class — a single instance is cached in the graph via
// `SuspendDoubleCheck`.

abstract class AppScope private constructor()

@SuspendAware
@Inject
@SingleIn(AppScope::class)
class AccountCreator(val database: String)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  suspend fun otherCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
