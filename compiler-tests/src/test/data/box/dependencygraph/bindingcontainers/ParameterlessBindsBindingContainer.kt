@DependencyGraph(bindingContainers = [TargetBindings::class])
interface AppGraph {
  val target: Target

  @Provides fun provideString(): String = "Hello"
}

@BindingContainer
interface TargetBindings {
  @Binds fun bindTarget(): Target
}

@Inject class Target(val value: String)

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Hello", graph.target.value)
  return "OK"
}
