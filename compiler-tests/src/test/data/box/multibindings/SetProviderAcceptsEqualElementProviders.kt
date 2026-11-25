// Bound instances with equal values produce equal providers in a Provider<Set<T>>.

@DependencyGraph
interface AppGraph {
  val strings: Provider<Set<String>>

  @Binds @IntoSet val @receiver:Named("a") String.bindA: String
  @Binds @IntoSet val @receiver:Named("b") String.bindB: String

  @Provides fun unqualified(): String = "other"
  @Binds @IntoSet val String.bindUnqualified: String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides @Named("a") a: String, @Provides @Named("b") b: String): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create("value", "value")
  assertEquals(setOf("value", "other"), graph.strings())
  return "OK"
}
