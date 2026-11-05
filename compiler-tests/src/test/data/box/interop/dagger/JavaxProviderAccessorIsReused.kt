// ENABLE_DAGGER_INTEROP
// A javax.inject.Provider accessor should return the same provider on each call instead of a new
// factory and wrapper.

@Inject class Bar

@Inject class Foo(val bar: Bar)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val fooProvider: javax.inject.Provider<Foo>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.fooProvider, graph.fooProvider)
  return "OK"
}
