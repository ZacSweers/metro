// ENABLE_SUSPEND_PROVIDERS

// Only the child consumes this parent map. The parent must finish collecting both the local and
// cross-module contributors before the child queries whether the map requires suspension.

// MODULE: lib
interface Handler {
  fun db(): String
}

@ContributesIntoMap(AppScope::class)
@StringKey("suspend")
@Inject
class AuthHandler(val database: String) : Handler {
  override fun db() = database
}

// MODULE: main(lib)
@file:Suppress("OPT_IN_USAGE")

abstract class ChildScope private constructor()

object SyncHandler : Handler {
  override fun db() = "sync"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val handlers: Map<String, SuspendProvider<Handler>>

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides suspend fun provideDatabase(): String = "db"

  @Provides
  @IntoMap
  @StringKey("sync")
  fun provideSyncHandler(): Handler = SyncHandler
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.createChild()
  return runBlocking {
    assertEquals(setOf("suspend", "sync"), child.handlers.keys)
    assertEquals("db", child.handlers.getValue("suspend").invoke().db())
    assertEquals("sync", child.handlers.getValue("sync").invoke().db())
    "OK"
  }
}
