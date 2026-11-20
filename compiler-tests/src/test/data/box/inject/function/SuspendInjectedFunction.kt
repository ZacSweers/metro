// WITH_COROUTINES
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

@Inject
suspend fun App(message: String): String = message

@DependencyGraph
interface ExampleGraph {
  val app: App

  @Provides private fun provideMessage(): String = "Hello, world!"
}

// A suspend caller exercises the generated suspend invoke.
fun box(): String = runBlocking {
  val app = createGraph<ExampleGraph>().app
  val invoke: suspend () -> String = app::invoke
  assertEquals("Hello, world!", invoke())
  "OK"
}
