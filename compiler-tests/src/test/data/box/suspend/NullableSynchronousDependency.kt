// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface ExampleGraph {
  suspend fun result(): Result<String?>

  @Provides fun provideNullableValue(): String? = null

  @Provides suspend fun provideResult(value: String?): Result<String?> = Result.success(value)
}

fun box(): String =
  runBlocking {
    val graph = createGraph<ExampleGraph>()
    assertNull(graph.result().getOrThrow())
    "OK"
  }
