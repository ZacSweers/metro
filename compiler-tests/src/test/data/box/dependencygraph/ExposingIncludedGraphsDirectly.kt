// https://github.com/ZacSweers/metro/issues/1144

@DependencyGraph
interface PushComponent {
  fun foo(): Foo

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes foo: Foo): PushComponent
  }
}

interface Foo {
  val a: String
}

fun box(): String {
  val foo = object : Foo {
    override val a: String = "hello"
  }
  val graph = createGraphFactory<PushComponent.Factory>().create(foo)
  assertSame(foo, graph.foo())
  return "OK"
}