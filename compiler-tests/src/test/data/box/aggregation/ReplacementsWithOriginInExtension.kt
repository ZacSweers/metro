abstract class Foo {
  abstract val value: String
}

@Origin(Foo::class)
class IntermediateGeneratedFoo

@Origin(IntermediateGeneratedFoo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedFoo : Foo() {
  override val value = "real"
}

@Inject
@ContributesBinding(scope = AppScope::class, replaces = [Foo::class])
class FakeFoo : Foo() {
  override val value = "fake"
}

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
  assertEquals("fake", graph.foo.value)
  return "OK"
}
