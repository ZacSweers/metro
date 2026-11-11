// Two @IntoSet aliases of one scoped binding contribute the same instance to a Provider<Set<T>>.

interface Base

@SingleIn(AppScope::class) @Inject class Impl : Base

@DependencyGraph(AppScope::class)
interface AppGraph {
  val bases: Provider<Set<Base>>

  @Binds @IntoSet val Impl.bindA: Base
  @Binds @IntoSet val Impl.bindB: Base
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(1, graph.bases().size)
  return "OK"
}
