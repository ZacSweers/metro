interface Foo

@Origin(RealFoo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedRealFoo : RealFoo()

abstract class RealFoo : Foo

@Inject
@ContributesBinding(scope = AppScope::class, replaces = [RealFoo::class])
class FakeFoo : Foo

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("FakeFoo", graph.foo::class.qualifiedName)
  return "OK"
}
