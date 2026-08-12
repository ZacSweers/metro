abstract class Foo {
  abstract val value: String
}

@Origin(Foo::class)
@Inject
@ContributesBinding(AppScope::class)
class GeneratedFoo : Foo() {
  override val value = "real"
}

@Inject
@ContributesBinding(AppScope::class)
class FakeFoo : Foo() {
  override val value = "fake"
}

@DependencyGraph(AppScope::class, excludes = [Foo::class])
interface AppGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("fake", graph.foo.value)
  return "OK"
}
