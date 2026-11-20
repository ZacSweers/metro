package test

interface ContributedInterface

@ContributesIntoMap(AppScope::class)
@StringKey("Impl1")
object Impl1 : ContributedInterface

@ContributesIntoMap(AppScope::class)
@StringKey("Impl2")
object Impl2 : ContributedInterface

@DependencyGraph(scope = AppScope::class, excludes = [Impl1::class])
interface ExampleGraph {
  val contributedInterfaces: Map<String, ContributedInterface>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(1, graph.contributedInterfaces.size)
  return "OK"
}
