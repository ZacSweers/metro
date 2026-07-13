// ENABLE_SUSPEND_PROVIDERS

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// A constructor-injected class with an unwrapped suspend dep, accessed via a suspend graph
// accessor. The graph inlines construction in suspend context and resolves the suspend dep
// inline.

@Inject class Foo(val dep: String)

@DependencyGraph
interface ExampleGraph {
  suspend fun foo(): Foo

  @Provides suspend fun provideString(): String = "hello"
}

private fun <T> runSuspending(block: suspend () -> T): T {
  var result: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
  return result!!.getOrThrow()
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("hello", runSuspending { graph.foo() }.dep)
  return "OK"
}
