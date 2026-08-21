// Verify that multiple @ContributesBinding on the same class works.
// Each binding should produce a separate provides function with the correct return type, and
// replacing one binding by priority must not remove the other.

// MODULE: common
interface Foo
interface Bar

// MODULE: lib(common)
@ContributesBinding(AppScope::class, binding = binding<Foo>())
@ContributesBinding(AppScope::class, binding = binding<Bar>())
@Inject
class FooBarImpl : Foo, Bar

// MODULE: main(lib, common)
@ContributesBinding(AppScope::class, priority = 100)
@Inject
class PreferredFoo : Foo

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
  val bar: Bar
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("PreferredFoo", graph.foo::class.simpleName)
  assertEquals("FooBarImpl", graph.bar::class.simpleName)
  return "OK"
}
