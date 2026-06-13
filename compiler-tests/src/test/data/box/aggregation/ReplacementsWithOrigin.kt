interface Foo

@Origin(RealFoo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedRealFoo : RealFoo() {
  override fun toString() = "real"
}

abstract class RealFoo : Foo

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
