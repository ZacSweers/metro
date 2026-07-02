// A transitively-suspend constructor-injected class accessed as SuspendProvider<T> — exercises
// the graph's IR-only nested SuspendFactory (not the inline canBypassFactory path). The nested
// factory holds each suspend dep as a SuspendProvider<…> field and awaits them in its suspend
// invoke().

@Inject
class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  val creatorProvider: SuspendProvider<AccountCreator>

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.creatorProvider)
  return "OK"
}
