// ENABLE_SUSPEND_PROVIDERS

// A @ContributesBinding implementation whose constructor consumes a suspend binding. The bound
// type is transitively suspend and must be accessed through a suspend accessor.

// MODULE: lib
interface Repository {
  fun db(): String
}

@ContributesBinding(AppScope::class)
@Inject
class RealRepository(val database: String) : Repository {
  override fun db() = database
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  suspend fun repository(): Repository

  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    val repo = graph.repository()
    assertEquals("RealRepository", repo::class.simpleName)
    assertEquals("db", repo.db())
    "OK"
  }
}
