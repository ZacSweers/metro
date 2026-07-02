// `@SuspendAware` class whose constructor explicitly takes a `SuspendProvider<T>` (deferred
// resolution). The factory ctor field is the same SuspendProvider; the graph passes through.

@SuspendAware
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
