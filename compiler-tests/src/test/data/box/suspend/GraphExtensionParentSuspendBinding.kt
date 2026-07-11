// ENABLE_SUSPEND_PROVIDERS

// WITH_RUNTIME_COROUTINES
// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

// A graph extension consuming a scoped suspend binding owned by its parent. The parent stores the
// binding in a SuspendProvider<T> field; the child's property-access token must classify it as a
// suspend provider, not a scalar instance.

var dbComputations = 0

abstract class AppScope private constructor()

@DependencyGraph(scope = AppScope::class)
interface ParentGraph {
  suspend fun database(): String

  fun childGraphFactory(): ChildGraph.Factory

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDatabase(): String {
    dbComputations++
    return "db"
  }
}

@Inject class Repository(val database: String)

@GraphExtension
interface ChildGraph {
  suspend fun repository(): Repository

  suspend fun database(): String

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  val child = parent.childGraphFactory().create()
  return kotlinx.coroutines.runBlocking {
    assertEquals("db", child.repository().database)
    assertEquals("db", child.database())
    assertEquals("db", parent.database())
    // One shared computation across parent and child
    assertEquals(1, dbComputations)
    "OK"
  }
}
