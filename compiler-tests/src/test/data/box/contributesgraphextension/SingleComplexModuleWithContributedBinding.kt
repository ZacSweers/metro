package test

abstract class LoggedInScope

interface ContributedInterface

@ContributesBinding(LoggedInScope::class)
@Inject
class Impl : ContributedInterface


@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val contributedInterface: ContributedInterface

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph

fun box(): String {
  val exampleGraph = createGraph<ExampleGraph>()
  val loggedInGraph = exampleGraph.createLoggedInGraph()
  assertNotNull(loggedInGraph.contributedInterface)
  return "OK"
}
