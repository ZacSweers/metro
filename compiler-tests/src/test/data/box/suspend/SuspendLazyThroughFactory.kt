// ENABLE_SUSPEND_PROVIDERS

// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

// SuspendLazy<T> params flowing through per-class factories (not inline bypass):
// - Consumer is SCOPED, so the graph goes through Consumer_Factory.create(...). The factory must
//   hold a SuspendProvider<T> field and memoize per invoke.
// - provideReport is a suspend @Provides taking SuspendLazy<T>, exercising the suspend-factory
//   parameter path.

var dbComputations = 0

abstract class AppScope private constructor()

@Inject
@SingleIn(AppScope::class)
class Consumer(val database: SuspendLazy<String>)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val consumer: Consumer

  suspend fun reportLength(): Int

  @Provides
  suspend fun provideDatabase(): String {
    dbComputations++
    return "db"
  }

  @Provides
  suspend fun provideReportLength(db: SuspendLazy<String>): Int = db.value().length
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return kotlinx.coroutines.runBlocking {
    val consumer = graph.consumer
    assertEquals("db", consumer.database.value())
    assertEquals("db", consumer.database.value())
    assertEquals(1, dbComputations)

    assertEquals(2, graph.reportLength())
    // Unscoped String — the report's own SuspendLazy recomputes
    assertEquals(2, dbComputations)
    "OK"
  }
}
