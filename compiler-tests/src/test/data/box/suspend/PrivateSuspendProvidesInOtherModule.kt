// ENABLE_SUSPEND_PROVIDERS

// A private suspend @Provides function is called through its generated factory in a downstream
// module. The downstream factory stub must preserve the named creator function's suspend
// signature.

// MODULE: lib
interface Providers {
  @Provides
  @SingleIn(AppScope::class)
  private suspend fun provideString(): String = "Hello"
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph : Providers {
  suspend fun string(): String
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    assertEquals("Hello", graph.string())
    "OK"
  }
}
