// Verify that @GraphExtension without an explicit scope works when contributed
// to a parent graph. Previously the compiler assumed all contributed graph extensions
// had aggregation scopes, which would crash during graph generation.
// https://github.com/ZacSweers/metro/pull/883

@GraphExtension
interface UnscopedExtension {
  val int: Int

  @Provides fun provideInt(): Int = 42

  @GraphExtension.Factory @ContributesTo(Unit::class)
  interface Factory {
    fun createUnscopedExtension(): UnscopedExtension
  }
}

@DependencyGraph(Unit::class)
interface ParentGraph

fun box(): String {
  val graph = createGraph<ParentGraph>().createUnscopedExtension()
  assertEquals(42, graph.int)
  return "OK"
}
