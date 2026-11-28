@Inject class Bar

@Inject class Foo(val bar: Bar)

@Inject class Baz(val foo: Foo)

// Foo is read twice, so Metro generates a shared getter property `foo`.
// Its JVM getter `getFoo()` must not clash with the accessor below.
@DependencyGraph
interface AppGraph {
  fun getFoo(): Foo

  val baz: Baz
}

interface FooAccessor {
  fun getFoo(): Foo
}

@DependencyGraph
interface InheritingGraph : FooAccessor {
  val baz: Baz
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.getFoo())
  assertNotNull(graph.baz.foo)

  val inheritingGraph = createGraph<InheritingGraph>()
  assertNotNull(inheritingGraph.getFoo())
  assertNotNull(inheritingGraph.baz.foo)
  return "OK"
}
