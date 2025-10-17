interface Foo

@Origin(RealFoo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedRealFoo : RealFoo()

abstract class RealFoo : Foo

@Inject
@ContributesBinding(scope = AppScope::class, replaces = [RealFoo::class])
class FakeFoo : Foo

@DependencyGraph(Unit::class)
interface UnitGraph {
  val appGraph: AppGraph
}

@GraphExtension(AppScope::class)
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<UnitGraph>().appGraph
  assertEquals("FakeFoo", graph.foo::class.qualifiedName)
  return "OK"
}
