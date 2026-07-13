// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

@Inject
class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  val creatorProvider: SuspendProvider<AccountCreator>

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

private fun runSuspending(block: suspend () -> String): String {
  var result: Result<String>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String =
  runSuspending {
    val creator = createGraph<ExampleGraph>().creatorProvider()
    assertEquals("db", creator.database)
    assertEquals(7, creator.tlsConnection)
    "OK"
  }
