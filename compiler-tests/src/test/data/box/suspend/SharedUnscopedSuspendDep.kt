// A transitively-suspend, UNSCOPED binding consumed as a scalar by multiple suspend accessors.
// The property collector must not give it a (non-suspend) GETTER property — it needs a
// SuspendProvider<T> field so each consumer awaits it in its own suspend context.

@Inject class Database(val url: String, val port: Int)

@Inject class ReadClient(val database: Database)

@Inject class WriteClient(val database: Database)

@DependencyGraph
interface ExampleGraph {
  suspend fun readClient(): ReadClient

  suspend fun writeClient(): WriteClient

  @Provides suspend fun provideUrl(): String = "db://localhost"

  @Provides fun providePort(): Int = 5432
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph)
  return "OK"
}
