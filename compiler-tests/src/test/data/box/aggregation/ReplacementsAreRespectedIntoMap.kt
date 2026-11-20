package test

interface ContributedInterface

@ContributesIntoMap(AppScope::class)
@StringKey("Impl1")
object Impl1 : ContributedInterface

@ContributesIntoMap(AppScope::class, replaces = [Impl1::class])
@StringKey("Impl2")
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterfaces: Map<String, ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(1, graph.contributedInterfaces.size)
  return "OK"
}
