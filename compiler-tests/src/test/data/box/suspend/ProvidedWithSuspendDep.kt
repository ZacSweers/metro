// A suspend @Provides may take an unwrapped suspend binding as a parameter. The factory's
// ctor field is `SuspendProvider<…>` so the graph can pass the suspend dep directly, and the
// suspend factory's invoke body awaits each field before calling the function.

@DependencyGraph
interface ExampleGraph {
  val provider: SuspendProvider<Int>

  @Provides suspend fun provideInt(dep: String): Int = dep.length

  @Provides suspend fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.provider)
  return "OK"
}
