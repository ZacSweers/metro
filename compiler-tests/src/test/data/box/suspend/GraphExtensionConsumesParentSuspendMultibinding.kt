// ENABLE_SUSPEND_PROVIDERS

// A graph extension consuming a parent map multibinding whose contributor is backed by a suspend
// binding. Only the child consumes the map, so the child must query the parent's multibinding
// before the parent is sealed.

interface Handler {
  fun db(): String
}

@ContributesIntoMap(AppScope::class)
@StringKey("auth")
@Inject
class AuthHandler(val database: String) : Handler {
  override fun db() = database
}

abstract class ChildScope private constructor()

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
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.createChild()
  return runBlocking {
    assertEquals(setOf("auth"), child.handlers.keys)
    val handler = child.handlers.getValue("auth").invoke()
    assertEquals("db", handler.db())
    "OK"
  }
}
