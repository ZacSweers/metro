// Verifies `@SuspendAware` factory shape works when the class is requested as
// `SuspendProvider<T>` (which exercises the factory's create() and invoke() — not the inline
// canBypassFactory path). The factory ctor takes `SuspendProvider<…>` fields, so the graph can
// pass suspend deps through.

@SuspendAware
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
