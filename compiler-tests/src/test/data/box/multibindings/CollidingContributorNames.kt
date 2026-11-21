@DependencyGraph
interface Graph {
  // The names in each pair have the same String.hashCode().
  @Provides @IntoSet fun provideAa(): String = "first"
  @Provides @IntoSet fun provideBB(): String = "second"

  @Provides @IntoMap @StringKey("a") fun mapAa(): String = "first"
  @Provides @IntoMap @StringKey("b") fun mapBB(): String = "second"

  val elements: Set<String>
  val entries: Map<String, String>
}

fun box(): String {
  val graph = createGraph<Graph>()
  assertEquals(setOf("first", "second"), graph.elements)
  assertEquals(mapOf("a" to "first", "b" to "second"), graph.entries)
  return "OK"
}
