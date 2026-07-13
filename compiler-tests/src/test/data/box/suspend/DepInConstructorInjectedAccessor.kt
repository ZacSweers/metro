// ENABLE_SUSPEND_PROVIDERS

// A constructor-injected class with an unwrapped suspend dep, accessed via a suspend graph
// accessor. The graph inlines construction in suspend context and resolves the suspend dep
// inline.

@Inject class Foo(val dep: String)

@DependencyGraph
interface ExampleGraph {
  suspend fun foo(): Foo

  @Provides suspend fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("hello", runSuspending { graph.foo() }.dep)
  return "OK"
}
