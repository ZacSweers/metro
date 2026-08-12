// Regression test for https://github.com/ZacSweers/metro/discussions/2644
// The origin target is the bound type itself, so a surviving contribution is observable
// as a duplicate binding.
interface Foo

class RealFoo

@Origin(RealFoo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedRealFoo : Foo {
  override fun toString() = "real"
}

@Inject
@ContributesBinding(scope = AppScope::class, replaces = [RealFoo::class])
class FakeFoo : Foo {
  override fun toString() = "fake"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("fake", graph.foo.toString())
  return "OK"
}
