// A dynamic container's @IntoSet contribution joins the graph's own contributions.
@DependencyGraph
interface AppGraph {
  val strings: Set<String>

  @Provides @IntoSet fun provideString(): String = "real"
}

@BindingContainer
object FakeStrings {
  @Provides @IntoSet fun provideString(): String = "fake"
}

fun box(): String {
  val graph = createDynamicGraph<AppGraph>(FakeStrings)
  assertEquals(setOf("real", "fake"), graph.strings)
  return "OK"
}
