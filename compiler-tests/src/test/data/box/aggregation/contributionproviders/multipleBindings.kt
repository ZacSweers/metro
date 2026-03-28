// Verify that multiple @ContributesBinding on the same class works.
// Each binding should produce a separate provides function with the correct return type.

interface Foo
interface Bar

@ContributesBinding(AppScope::class, binding = binding<Foo>())
@ContributesBinding(AppScope::class, binding = binding<Bar>())
@Inject
class FooBarImpl : Foo, Bar

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
  val bar: Bar
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("FooBarImpl", graph.foo::class.simpleName)
  assertEquals("FooBarImpl", graph.bar::class.simpleName)
  return "OK"
}
