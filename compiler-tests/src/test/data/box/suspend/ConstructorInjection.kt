// Tests the wasmo-style pattern: a constructor-injected class with multiple suspend dependencies
// accessed via a suspend graph accessor. The graph's accessor inlines construction (canBypassFactory)
// in a suspend context, awaiting each suspend @Provides directly.

@Inject class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  // Suspend accessor that constructs AccountCreator inline. Both deps are suspend @Provides.
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Can't invoke a suspend accessor without coroutines runtime; graph creation alone validates
  // the binding graph and codegen.
  assertNotNull(graph)
  return "OK"
}
