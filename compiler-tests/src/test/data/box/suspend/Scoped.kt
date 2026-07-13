// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

var databaseComputations = 0

abstract class AppScope private constructor()

@Inject
@SingleIn(AppScope::class)
class AccountCreator(val database: String)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  suspend fun otherCreator(): AccountCreator

  @Provides
  suspend fun provideDatabase(): String {
    databaseComputations++
    return "db"
  }
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String =
  runSuspending {
    databaseComputations = 0
    val graph = createGraph<ExampleGraph>()
    val accountCreator = graph.accountCreator()
    val otherCreator = graph.otherCreator()

    assertEquals("db", accountCreator.database)
    assertSame(accountCreator, otherCreator)
    assertEquals(1, databaseComputations)
    "OK"
  }
