// ENABLE_SUSPEND_PROVIDERS

// WITH_RUNTIME_COROUTINES
// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

// SuspendLazy<T> as an injectable wrapper: it defers and memoizes a suspend binding per wrapper
// instance. Like `suspend () -> T`, it breaks the suspend chain, so consumers are not suspend.

var dbComputations = 0

@Inject
class Consumer(val database: SuspendLazy<String>, val port: SuspendLazy<Int>)

@DependencyGraph
interface ExampleGraph {
  // Non-suspend accessors — SuspendLazy defers, so these are legal
  val database: SuspendLazy<String>
  val consumer: Consumer

  @Provides
  suspend fun provideDatabase(): String {
    dbComputations++
    return "db"
  }

  // Non-suspend bindings can be wrapped too
  @Provides fun providePort(): Int = 5432
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return kotlinx.coroutines.runBlocking {
    val lazyDb = graph.database
    assertFalse(lazyDb.isInitialized())
    assertEquals("db", lazyDb.value())
    assertTrue(lazyDb.isInitialized())
    // Memoized per wrapper instance
    assertEquals("db", lazyDb.value())
    assertEquals(1, dbComputations)

    // Unscoped binding: a separate wrapper instance recomputes
    val consumer = graph.consumer
    assertEquals("db", consumer.database.value())
    assertEquals(2, dbComputations)

    // Non-suspend binding through SuspendLazy
    assertEquals(5432, consumer.port.value())
    "OK"
  }
}
