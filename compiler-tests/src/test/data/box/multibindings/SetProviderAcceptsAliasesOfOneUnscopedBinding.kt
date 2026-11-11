// Two @IntoSet aliases of one unscoped binding contribute two instances to a Provider<Set<T>>.

interface Base

@Inject class Impl : Base

@DependencyGraph
interface AppGraph {
  val bases: Provider<Set<Base>>
  val basesInstance: Set<Base>

  @Binds @IntoSet val Impl.bindA: Base
  @Binds @IntoSet val Impl.bindB: Base
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(2, graph.basesInstance.size)
  assertEquals(2, graph.bases().size)
  return "OK"
}
