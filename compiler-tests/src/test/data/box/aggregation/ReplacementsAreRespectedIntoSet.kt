package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class)
object Impl1 : ContributedInterface

@ContributesIntoSet(AppScope::class, replaces = [Impl1::class])
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterfaces: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(1, graph.contributedInterfaces.size)
  return "OK"
}
