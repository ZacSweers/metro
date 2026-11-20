package test

interface ContributedInterface

@ContributesIntoSet(AppScope::class)
object Impl1 : ContributedInterface

@ContributesIntoSet(AppScope::class)
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class, excludes = [Impl1::class])
interface ExampleGraph {
  val contributedInterfaces: Set<ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(1, graph.contributedInterfaces.size)
  return "OK"
}
