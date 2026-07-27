// ENABLE_SUSPEND_PROVIDERS

// A scoped suspend @Provides in an upstream contributed container. Scoping forces the downstream
// graph to store a provider field, so it must retain the function's suspend metadata.

// MODULE: lib
@BindingContainer
@ContributesTo(AppScope::class)
object DatabaseContainer {
  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDatabase(): String = "db"
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
