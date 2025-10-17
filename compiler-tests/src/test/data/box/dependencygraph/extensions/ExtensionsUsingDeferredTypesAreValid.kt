// https://github.com/ZacSweers/metro/pull/977
@GraphExtension
interface LoggedInGraph {
  val foo: Foo
}

@DependencyGraph
@SingleIn(AppScope::class)
interface AppGraph {
  val loggedInGraph: LoggedInGraph
}

@Inject @SingleIn(AppScope::class) class Foo(val fooProvider: Provider<Foo>)

fun box(): String {
  val graph = createGraph<AppGraph>()
  val loggedInGraph = graph.loggedInGraph
  assertNotNull(loggedInGraph.foo)
  assertSame(loggedInGraph.foo, loggedInGraph.foo)
  return "OK"
}
