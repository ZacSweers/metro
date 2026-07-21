// ENABLE_SUSPEND_PROVIDERS

// A @ContributesTo binding container with a suspend @Provides, merged into a graph and accessed
// through a suspend accessor.

// MODULE: lib
@BindingContainer
@ContributesTo(AppScope::class)
object DatabaseContainer {
  @Provides suspend fun provideDatabase(): String = "db"
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  suspend fun database(): String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    assertEquals("db", graph.database())
    "OK"
  }
}
