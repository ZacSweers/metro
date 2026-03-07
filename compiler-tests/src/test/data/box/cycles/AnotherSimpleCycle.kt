// Really just here to mirror the dump test for switching providers

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val long: Long

  // Base case - provides initial value without dependencies
  @Provides
  fun provideInt(): Int = 42

  @SingleIn(AppScope::class)
  @Provides
  fun provideLong(string: String): Long = string.toLong()

  // Depends on Lazy<Long> to break cycle, and Int for base case
  @SingleIn(AppScope::class)
  @Provides
  fun provideString(int: Int, long: Lazy<Long>): String = int.toString()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("42", graph.string)
  assertEquals(42L, graph.long)
  return "OK"
}