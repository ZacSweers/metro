// ENABLE_SUSPEND_PROVIDERS

class Result(val value: String?)

@DependencyGraph
interface ExampleGraph {
  suspend fun result(): Result

  @Provides fun provideNullableValue(): String? = null

  @Provides suspend fun provideResult(value: String?): Result = Result(value)
}

fun box(): String {
  createGraph<ExampleGraph>()
  return "OK"
}
