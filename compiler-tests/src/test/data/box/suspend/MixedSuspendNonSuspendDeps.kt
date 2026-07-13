// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// A class mixing suspend and non-suspend deps. The non-suspend dep is adapted into the
// suspend-flavored slot via SyncSuspendProvider where needed.

@Inject
class AccountCreator(val database: String, val region: Long)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  // suspend
  @Provides suspend fun provideDatabase(): String = "db"

  // non-suspend (this is the CompositeProvider path)
  @Provides fun provideRegion(): Long = 42L
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String =
  runSuspending {
    val accountCreator = createGraph<ExampleGraph>().accountCreator()
    assertEquals("db", accountCreator.database)
    assertEquals(42L, accountCreator.region)
    "OK"
  }
