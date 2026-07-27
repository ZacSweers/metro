// ENABLE_SUSPEND_PROVIDERS

// Two same-typed suspend bindings distinguished by @Named qualifiers, plus a qualified suspend
// accessor and a consumer taking both. Exercises key identity in the suspend analysis and per-key
// field sharing.

@Inject
class Connection(
  @Named("primary") val primary: String,
  @Named("replica") val replica: String,
)

@DependencyGraph
interface ExampleGraph {
  @Named("primary") suspend fun primaryDb(): String

  suspend fun connection(): Connection

  @Provides @Named("primary") suspend fun providePrimary(): String = "primary-db"

  @Provides @Named("replica") suspend fun provideReplica(): String = "replica-db"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return runBlocking {
    assertEquals("primary-db", graph.primaryDb())
    val conn = graph.connection()
    assertEquals("primary-db", conn.primary)
    assertEquals("replica-db", conn.replica)
    "OK"
  }
}
