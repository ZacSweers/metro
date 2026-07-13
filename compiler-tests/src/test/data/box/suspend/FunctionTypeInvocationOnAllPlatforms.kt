// ENABLE_SUSPEND_PROVIDERS

// Invoking graph-provided suspend values through the `suspend () -> T` FUNCTION TYPE, on all
// platforms including JS. On Kotlin/JS a class instance implementing a function type is not a
// callable JS function — invocation through the function type compiles to a direct JS call and
// throws TypeError unless the compiler wraps the value in a real lambda (toSuspendFunctionType).
// The providers never actually suspend, so the test can use the compiler test framework's
// synchronous coroutine helper without depending on kotlinx-coroutines.

@Inject class Consumer(val messageProvider: suspend () -> String)

abstract class AppScope private constructor()

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val message: suspend () -> String

  val consumer: Consumer

  val lazyMessage: SuspendLazy<String>

  @Provides @SingleIn(AppScope::class) suspend fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()

  // Accessor value invoked through the suspend function type
  val fn: suspend () -> String = graph.message
  assertEquals("hello", runBlocking { fn() })

  // Injected `suspend () -> T` ctor param invoked through the function type
  val injected = graph.consumer.messageProvider
  assertEquals("hello", runBlocking { injected() })

  // SuspendLazy dispatches through its interface (never the function type) — sanity-check on JS
  assertEquals("hello", runBlocking { graph.lazyMessage.value() })

  return "OK"
}
