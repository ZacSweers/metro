// `@SuspendAware` opt-in lets a class take unwrapped suspend dependencies and have its
// generated factory be a `SuspendFactory<T>` with `SuspendProvider<…>` ctor params. The class
// stays in the suspend set so callers must access it via a suspend accessor.

@SuspendAware
@Inject
class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
