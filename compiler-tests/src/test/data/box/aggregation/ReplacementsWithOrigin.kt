abstract class Foo {
  abstract val value: String
}

typealias FooAlias = Foo

@Origin(FooAlias::class)
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

@DependencyGraph(AppScope::class)
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("fake", graph.foo.value)
  return "OK"
}
