// ENABLE_SUSPEND_PROVIDERS

// A class whose ctor param is already SuspendProvider<String> — the wrapper breaks the suspend
// chain, so the class itself is NOT in the suspend set and uses a plain factory. The graph passes
// the suspend binding through as a SuspendProvider.

@Inject
class AccountCreator(val database: SuspendProvider<String>)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
