package test

interface ContributedInterface

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class Impl1 : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertSame(graph.contributedInterface, graph.contributedInterface)
  return "OK"
}
