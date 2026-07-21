// ENABLE_SUSPEND_PROVIDERS

// A generic @AssistedFactory interface (class-level type parameter) whose target has a suspend
// constructor dep. The nested suspend impl is non-generic, so the SAM's type-parameter references
// must be remapped to the concrete argument the graph resolved (Holder<String> here).

class Database(val value: String)

@AssistedInject
class Holder<T>(@Assisted val id: Int, val database: Database) {
  @AssistedFactory
  interface Factory<T> {
    suspend fun create(id: Int): Holder<T>
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: Holder.Factory<String>

  @Provides suspend fun provideDatabase(): Database = Database("db")
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val holder = runBlocking { graph.factory.create(42) }
  assertEquals(42, holder.id)
  assertEquals("db", holder.database.value)
  return "OK"
}
