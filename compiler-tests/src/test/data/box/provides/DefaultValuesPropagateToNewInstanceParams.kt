@DependencyGraph
interface AppGraph {
  val int: Int
  @Provides fun provideInt(stringValue: String? = null): Int = stringValue?.toInt() ?: 3
}

fun box(): String {
  assertEquals(3, createGraph<AppGraph>().int)
  return "OK"
}