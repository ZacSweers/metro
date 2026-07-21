// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface ExampleGraph {
  val provider: suspend () -> Int

  @Provides suspend fun provideInt(dep: String): Int = dep.length

  @Provides suspend fun provideString(): String = "hello"
}

fun box(): String {
  val provider = createGraph<ExampleGraph>().provider
  return runBlocking {
    assertEquals(5, provider())
    "OK"
  }
}
