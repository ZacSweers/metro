// ENABLE_SUSPEND_PROVIDERS: true

// With `enableSuspendProviders` on, the bypass-factory inline path resolves multiple suspend
// deps in parallel via `coroutineScope { async { … } }.await()`. This test exercises the
// codegen by constructing a class with several suspend deps from a suspend accessor.

@Inject
class AccountCreator(val database: String, val tlsConnection: Int, val region: Long)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7

  @Provides suspend fun provideRegion(): Long = 42L
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
