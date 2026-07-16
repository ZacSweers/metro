// ENABLE_SUSPEND_PROVIDERS

// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runtime-coroutines is not yet wired into JS box tests

// SuspendLazy<T> over a SCOPED suspend binding shares the graph's cache: SuspendDoubleCheck.lazy
// short-circuits when the delegate is already the graph's SuspendDoubleCheck, so all consumers
// observe one computation. Also exercises a SuspendLazy ctor param inside a generated suspend
// factory (Mixed is transitively suspend via its unwrapped dep and multi-use).

var dbComputations = 0

abstract class AppScope private constructor()

@Inject
class Mixed(val lazyDb: SuspendLazy<String>, val db: String)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val database: SuspendLazy<String>

  suspend fun databaseValue(): String

  suspend fun mixed(): Mixed

  // Multi-use to route Mixed through a shared suspend factory
  suspend fun otherMixed(): Mixed

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideDatabase(): String {
    dbComputations++
    return "db"
  }
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return kotlinx.coroutines.runBlocking {
    val lazyDb = graph.database
    assertEquals("db", lazyDb.value())
    // Scoped: the suspend accessor shares the same cached instance, no recomputation
    assertEquals("db", graph.databaseValue())

    val mixed = graph.mixed()
    assertEquals("db", mixed.db)
    assertEquals("db", mixed.lazyDb.value())
    assertEquals("db", graph.otherMixed().lazyDb.value())

    assertEquals(1, dbComputations)
    "OK"
  }
}
