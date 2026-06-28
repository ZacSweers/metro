@DependencyGraph
interface AppGraph {
  val target: Target

  @Binds fun bindTarget(): Target

  @Provides fun provideString(): String = "Hello"
}

@Inject class Target(val value: String)

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.target.value)
  return "OK"
}
