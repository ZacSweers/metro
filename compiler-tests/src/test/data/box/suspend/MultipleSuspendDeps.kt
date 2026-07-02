// A constructor-injected class with several suspend deps, resolved sequentially by default
// (enableParallelSuspendResolution off).

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
