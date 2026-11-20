package test

interface ContributedInterface

abstract class LoggedInScope private constructor()

@ContributesBinding(LoggedInScope::class)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class, additionalScopes = [LoggedInScope::class])
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  return "OK"
}
