// ENABLE_SUSPEND_PROVIDERS

// A @ContributesIntoMap contribution whose value is transitively suspend (its constructor consumes
// a suspend binding), consumed as the deferred map form Map<K, suspend () -> V>.

// MODULE: lib
interface Handler {
  fun db(): String
}

@ContributesIntoMap(AppScope::class)
@StringKey("auth")
@Inject
class AuthHandler(val database: String) : Handler {
  override fun db() = database
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val handlers: Map<String, suspend () -> Handler>

  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    assertEquals(setOf("auth"), graph.handlers.keys)
    val handler = graph.handlers.getValue("auth").invoke()
    assertEquals("AuthHandler", handler::class.simpleName)
    assertEquals("db", handler.db())
    "OK"
  }
}
