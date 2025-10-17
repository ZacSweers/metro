// MODULE: lib
interface Foo

interface Bar : Foo {
  val str: String
}

@Inject
@ContributesBinding(AppScope::class, binding = binding<Foo>())
@ContributesBinding(AppScope::class, binding = binding<Bar>())
class RealImpl : Bar {
  override val str: String = "real"
}

@Inject
@ContributesBinding(AppScope::class, binding = binding<Foo>(), replaces = [RealImpl::class])
@ContributesBinding(AppScope::class, binding = binding<Bar>(), replaces = [RealImpl::class])
class FakeImpl : Bar {
  override val str: String = "fake"
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val bar: Bar
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(graph.bar.str, "fake")
  return "OK"
}