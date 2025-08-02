interface Foo {
  val str: String
}

interface Bar : Foo

abstract class OtherScope

@Inject
@ContributesBinding(OtherScope::class, binding = binding<Foo>())
@ContributesBinding(AppScope::class, binding = binding<Bar>())
class RealImpl : Bar {
  override val str: String = "real"
}

@Inject
@ContributesBinding(OtherScope::class, binding = binding<Foo>(), replaces = [RealImpl::class])
@ContributesBinding(AppScope::class, binding = binding<Bar>(), replaces = [RealImpl::class])
class FakeImpl : Bar {
  override val str: String = "fake"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val bar: Bar
}

@DependencyGraph(OtherScope::class)
interface OtherGraph {
  val foo: Foo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(graph.bar.str, "fake")
  val graph2 = createGraph<OtherGraph>()
  assertEquals(graph2.foo.str, "fake")
  return "OK"
}