// WITH_ANVIL

interface ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class)
object Impl1 : ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 10)
object Impl2 : ContributedInterface

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object Impl3 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertSame(Impl3, graph.contributedInterface)
  return "OK"
}
