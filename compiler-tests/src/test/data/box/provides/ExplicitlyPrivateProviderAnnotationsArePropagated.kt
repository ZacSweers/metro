// Regression test for https://github.com/ZacSweers/metro/issues/444
// MODULE: lib
private var count = 0
interface EnabledProvider {

  @Named("Hi")
  @SingleIn(AppScope::class)
  @Provides
  private fun provideInt(): Int = count++
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface ExampleGraph : EnabledProvider {
  @Named("Hi")
  val value: Int
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  // Scope annotation is properly recognized across compilation boundary
  assertEquals(0, graph.value)
  assertEquals(0, graph.value)
  return "OK"
}