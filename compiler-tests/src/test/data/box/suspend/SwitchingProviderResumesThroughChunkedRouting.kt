// ENABLE_SUSPEND_PROVIDERS
// ENABLE_SWITCHING_PROVIDERS: true
// STATEMENTS_PER_INIT_FUN: 1

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private var pending: Continuation<String>? = null

class Message(val value: String)

@DependencyGraph
interface ExampleGraph {
  val messageProvider: SuspendProvider<Message>
  val valueProvider: SuspendProvider<String>

  suspend fun message(): Message

  @Provides
  suspend fun provideValue(): String = suspendCoroutine { pending = it }

  @Provides
  fun provideMessage(value: String): Message = Message(value)
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  var result: Result<Message>? = null
  val call: suspend () -> Message = { graph.messageProvider() }
  call.startCoroutine(object : Continuation<Message> {
    override val context = EmptyCoroutineContext
    override fun resumeWith(outcome: Result<Message>) {
      result = outcome
    }
  })

  // The provider must remain suspended until the dependency resumes.
  val continuation = assertNotNull(pending)
  assertNull(result)
  pending = null
  continuation.resume("resumed")

  assertEquals("resumed", assertNotNull(result).getOrThrow().value)
  return "OK"
}
