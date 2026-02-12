// Extra coverage for nullable binding types with switching providers (via FastInitBoxTest).

@DependencyGraph(AppScope::class)
interface AppGraph {
  val nullableString: String?
  val nullableProvider: Provider<String?>

  @SingleIn(AppScope::class) @Provides fun provideNullableString(): String? = "Hello"
}

@DependencyGraph(AppScope::class)
interface NullGraph {
  val nullableString: String?
  val nullableProvider: Provider<String?>

  @SingleIn(AppScope::class) @Provides fun provideNullableString(): String? = null
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.nullableString)
  assertEquals("Hello", graph.nullableProvider.invoke())
  assertEquals("Hello", graph.nullableProvider.invoke())

  val nullGraph = createGraph<NullGraph>()
  assertEquals(null, nullGraph.nullableString)
  assertEquals(null, nullGraph.nullableProvider.invoke())
  assertEquals(null, nullGraph.nullableProvider.invoke())
  return "OK"
}
