@DependencyGraph(Unit::class)
interface ExampleGraph {
  val foo: Foo

  @Inject
  class Foo(val bar: Bar)

  @Inject
  class Bar(val value: String?)

  @Provides
  fun provideNullableString(): String? = "test"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("test", graph.foo.bar.value)
  return "OK"
}
