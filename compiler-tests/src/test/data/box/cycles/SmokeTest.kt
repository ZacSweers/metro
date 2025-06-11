@DependencyGraph
interface SmokeTestGraph {
  val bar: Bar

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides message: String): SmokeTestGraph
  }

  @Inject
  class Foo(val barProvider: Provider<Bar>)

  @Inject
  class Bar(val foo: Foo, val message: String)
}

fun box(): String {
  val graph = createGraphFactory<SmokeTestGraph.Factory>().create("Hello")
  val bar = graph.bar
  assertEquals(bar.message, "Hello")
  val foo = bar.foo
  val secondBar = foo.barProvider()
  assertEquals(secondBar.message, "Hello")
  assertNotSame(bar, secondBar)
  return "OK"
}