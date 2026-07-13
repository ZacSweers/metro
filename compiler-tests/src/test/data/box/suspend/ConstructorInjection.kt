// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// Tests the wasmo-style pattern: a constructor-injected class with multiple suspend dependencies
// accessed via a suspend graph accessor. The graph's accessor inlines construction (canBypassFactory)
// in a suspend context, awaiting each suspend @Provides directly.

@Inject class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  // Suspend accessor that constructs AccountCreator inline. Both deps are suspend @Provides.
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}

private fun <T> runSuspending(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val accountCreator = runSuspending { graph.accountCreator() }
  assertEquals("db", accountCreator.database)
  assertEquals(7, accountCreator.tlsConnection)
  return "OK"
}
