// https://github.com/ZacSweers/metro/issues/1549
// The important conditions in this test case:
// - There is a class RealImpl with one or more bindings
// - There is another class FakeImpl with the same binding as RealImpl, at least one other binding, and one of the bindings replaces RealImpl
// - The bindings are contributed to a graph extension, not the root graph
// MODULE: lib
interface Foo

interface Bar

abstract class LoggedInScope private constructor()

@Inject
@ContributesBinding(LoggedInScope::class, binding = binding<Bar>())
class RealImpl : Bar

@Inject
@ContributesBinding(LoggedInScope::class, binding = binding<Foo>())
@ContributesBinding(LoggedInScope::class, binding = binding<Bar>(), replaces = [RealImpl::class])
class FakeImpl : Bar, Foo

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {

  val foo: Foo
  val bar: Bar

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val loggedInGraph = appGraph.createLoggedInGraph()
  assertTrue(loggedInGraph.foo is FakeImpl)
  assertTrue(loggedInGraph.bar is FakeImpl)
  return "OK"
}
